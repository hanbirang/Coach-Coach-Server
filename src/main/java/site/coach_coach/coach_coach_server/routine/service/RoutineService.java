package site.coach_coach.coach_coach_server.routine.service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import site.coach_coach.coach_coach_server.coach.domain.Coach;
import site.coach_coach.coach_coach_server.coach.repository.CoachRepository;
import site.coach_coach.coach_coach_server.common.constants.ErrorMessage;
import site.coach_coach.coach_coach_server.common.domain.RelationFunctionEnum;
import site.coach_coach.coach_coach_server.common.exception.AccessDeniedException;
import site.coach_coach.coach_coach_server.common.exception.NotFoundException;
import site.coach_coach.coach_coach_server.matching.domain.Matching;
import site.coach_coach.coach_coach_server.matching.repository.MatchingRepository;
import site.coach_coach.coach_coach_server.notification.service.NotificationService;
import site.coach_coach.coach_coach_server.routine.domain.Routine;
import site.coach_coach.coach_coach_server.routine.dto.CreateRoutineRequest;
import site.coach_coach.coach_coach_server.routine.dto.RoutineCreatorDto;
import site.coach_coach.coach_coach_server.routine.dto.RoutineDto;
import site.coach_coach.coach_coach_server.routine.dto.RoutineListDto;
import site.coach_coach.coach_coach_server.routine.dto.UpdateRoutineInfoRequest;
import site.coach_coach.coach_coach_server.routine.repository.RoutineRepository;
import site.coach_coach.coach_coach_server.sport.domain.Sport;
import site.coach_coach.coach_coach_server.sport.repository.SportRepository;
import site.coach_coach.coach_coach_server.user.domain.User;
import site.coach_coach.coach_coach_server.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoutineService {
	private final RoutineRepository routineRepository;
	private final MatchingRepository matchingRepository;
	private final CoachRepository coachRepository;
	private final UserRepository userRepository;
	private final SportRepository sportRepository;
	private final NotificationService notificationService;
	private int numberOfCompletedRoutine;

	public void checkIsMatching(Long userId, Long coachId) {
		matchingRepository.findByUser_UserIdAndCoach_CoachId(userId, coachId)
			.map(Matching::getIsMatching)
			.filter(isMatching -> isMatching)
			.orElseThrow(() -> new NotFoundException(ErrorMessage.NOT_FOUND_MATCHING));
	}

	private RoutineCreatorDto setRoutineCreatorDto(Long userIdParam, Long coachIdParam, Long userIdByJwt) {
		if (coachIdParam == null) {
			Long coachId = getCoachId(userIdByJwt);
			return new RoutineCreatorDto(userIdParam, coachId);
		} else {
			return new RoutineCreatorDto(userIdByJwt, coachIdParam);
		}
	}

	public Long getCoachId(Long userIdByJwt) {
		return coachRepository.findCoachIdByUserId(userIdByJwt)
			.orElseThrow(() -> new NotFoundException(ErrorMessage.NOT_FOUND_COACH));
	}

	public RoutineCreatorDto confirmIsMatching(Long userIdParam, Long coachIdParam, Long userIdByJwt) {
		if (coachIdParam == null && userIdParam == null) {
			return new RoutineCreatorDto(userIdByJwt, null);
		} else {
			RoutineCreatorDto request = setRoutineCreatorDto(userIdParam, coachIdParam, userIdByJwt);
			checkIsMatching(request.userId(), request.coachId());
			return request;
		}
	}

	@Transactional(readOnly = true)
	public RoutineListDto getRoutineList(Long userIdParam, Long coachIdParam, Long userIdByJwt) {
		numberOfCompletedRoutine = 0;

		RoutineCreatorDto routineCreatorDto = confirmIsMatching(userIdParam, coachIdParam, userIdByJwt);

		RoutineListDto routineListDto = new RoutineListDto(0, new ArrayList<>());
		List<Routine> routines;

		if (routineCreatorDto.coachId() == null) {
			routines = routineRepository.findByUser_UserIdAndCoach_CoachIdIsNull(routineCreatorDto.userId());
		} else {
			routines = routineRepository.findByUser_UserIdAndCoach_CoachId(routineCreatorDto.userId(),
				routineCreatorDto.coachId());
		}
		ZonedDateTime nowInKorea = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
		routines.forEach((routine) -> {
			RoutineDto dto = RoutineDto.convertToDtoWithoutNullAction(routine);
			if (dto.repeats().contains(nowInKorea.getDayOfWeek())) {
				if (dto.isCompleted()) {
					numberOfCompletedRoutine += 1;
				}
				routineListDto.routines().add(dto);
			}
		});

		if (!routineListDto.routines().isEmpty() && numberOfCompletedRoutine != 0) {
			return routineListDto.setCompletionPercentage(
				(Math.round((float)numberOfCompletedRoutine / routineListDto.routines().size() * 100) / 100.0f));
		} else {
			return routineListDto.setCompletionPercentage(0.0f);
		}
	}

	@Transactional
	public Routine createRoutine(CreateRoutineRequest createRoutineRequest, Long userIdByJwt) {

		User user = userRepository.findById(
				createRoutineRequest.userId() == null ? userIdByJwt : createRoutineRequest.userId())
			.orElseThrow(() -> new NotFoundException(ErrorMessage.NOT_FOUND_USER));

		Sport sport = Sport.builder()
			.sportId(createRoutineRequest.sportId())
			.build();

		Routine.RoutineBuilder routineBuilder = Routine.builder()
			.user(user)
			.routineName(createRoutineRequest.routineName())
			.sport(sport)
			.isCompleted(false)
			.isDeleted(false)
			.repeats(createRoutineRequest.repeats());

		if (createRoutineRequest.userId() != null) {
			Long coachId = getCoachId(userIdByJwt);
			checkIsMatching(createRoutineRequest.userId(), coachId);
			Coach coach = coachRepository.findById(coachId)
				.orElseThrow(() -> new NotFoundException(ErrorMessage.NOT_FOUND_COACH));
			routineBuilder.coach(coach);
			notificationService.createNotification(createRoutineRequest.userId(), userIdByJwt,
				RelationFunctionEnum.routine);
		}

		return routineRepository.save(routineBuilder.build());
	}

	@Transactional
	public void deleteRoutine(Long routineId, Long userIdByJwt) {
		validateIsMyRoutine(routineId, userIdByJwt);
		routineRepository.deleteById(routineId);
	}

	@Transactional
	public void updateRoutine(UpdateRoutineInfoRequest updateRoutineInfoRequest, Long routineId, Long userIdByJwt) {
		Routine routine = validateIsMyRoutine(routineId, userIdByJwt);

		Sport sport = sportRepository.findById(updateRoutineInfoRequest.sportId())
			.orElseThrow(() -> new NotFoundException(ErrorMessage.NOT_FOUND_SPORTS));
		routine.updateRoutineInfo(updateRoutineInfoRequest, sport);
	}

	@Transactional
	public Routine validateIsMyRoutine(Long routineId, Long userIdByJwt) {
		Routine routine = routineRepository.findById(routineId)
			.orElseThrow(() -> new NotFoundException(ErrorMessage.NOT_FOUND_ROUTINE));

		if (routine.getCoach() == null) {
			if (!routine.getUser().getUserId().equals(userIdByJwt)) {
				throw new AccessDeniedException();
			}
		} else {
			Long coachId = getCoachId(userIdByJwt);
			if (!routine.getCoach().getCoachId().equals(coachId)) {
				throw new AccessDeniedException();
			}
		}
		return routine;
	}
}
