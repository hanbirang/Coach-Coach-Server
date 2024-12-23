package site.coach_coach.coach_coach_server.userrecord.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import site.coach_coach.coach_coach_server.common.constants.ErrorMessage;
import site.coach_coach.coach_coach_server.common.exception.AccessDeniedException;
import site.coach_coach.coach_coach_server.common.exception.DuplicateValueException;
import site.coach_coach.coach_coach_server.common.exception.InvalidInputException;
import site.coach_coach.coach_coach_server.common.exception.NotFoundException;
import site.coach_coach.coach_coach_server.common.exception.UserNotFoundException;
import site.coach_coach.coach_coach_server.completedroutine.domain.CompletedRoutine;
import site.coach_coach.coach_coach_server.completedroutine.dto.CompletedRoutineDto;
import site.coach_coach.coach_coach_server.completedroutine.repository.CompletedRoutineRepository;
import site.coach_coach.coach_coach_server.user.domain.User;
import site.coach_coach.coach_coach_server.user.repository.UserRepository;
import site.coach_coach.coach_coach_server.userrecord.domain.UserRecord;
import site.coach_coach.coach_coach_server.userrecord.dto.BodyInfoChartResponse;
import site.coach_coach.coach_coach_server.userrecord.dto.RecordResponse;
import site.coach_coach.coach_coach_server.userrecord.dto.RecordsDto;
import site.coach_coach.coach_coach_server.userrecord.dto.UserRecordCreateRequest;
import site.coach_coach.coach_coach_server.userrecord.dto.UserRecordDetailResponse;
import site.coach_coach.coach_coach_server.userrecord.dto.UserRecordDetailV2Response;
import site.coach_coach.coach_coach_server.userrecord.dto.UserRecordResponse;
import site.coach_coach.coach_coach_server.userrecord.dto.UserRecordUpdateRequest;
import site.coach_coach.coach_coach_server.userrecord.repository.UserRecordRepository;

@Service
@RequiredArgsConstructor
public class UserRecordService {
	private final UserRecordRepository userRecordRepository;
	private final UserRepository userRepository;
	private final CompletedRoutineRepository completedRoutineRepository;

	@Transactional
	public Long addBodyInfoToUserRecord(Long userId, UserRecordCreateRequest userRecordCreateRequest) {
		String date = userRecordCreateRequest.recordDate();

		LocalDate recordDate = validateAndConvertToLocalDate(date);
		User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

		if (userRecordRepository.existsByRecordDateAndUser_UserId(recordDate, userId)) {
			throw new DuplicateValueException(ErrorMessage.DUPLICATE_RECORD);
		}

		UserRecord userRecord = UserRecord.builder()
			.user(user)
			.weight(userRecordCreateRequest.weight())
			.skeletalMuscle(userRecordCreateRequest.skeletalMuscle())
			.fatPercentage(userRecordCreateRequest.fatPercentage())
			.bmi(userRecordCreateRequest.bmi())
			.recordDate(recordDate)
			.build();
		userRecordRepository.save(userRecord);

		return userRecord.getUserRecordId();
	}

	@Transactional
	public void updateBodyInfoToUserRecord(Long userId, Long recordId,
		UserRecordUpdateRequest userRecordUpdateRequest) {
		UserRecord userRecord = userRecordRepository.findById(recordId)
			.orElseThrow(() -> new NotFoundException(ErrorMessage.NOT_FOUND_RECORD));

		if (!userRecord.getUser().getUserId().equals(userId)) {
			throw new AccessDeniedException();
		}

		userRecord.updateBodyInfo(
			userRecordUpdateRequest.weight(),
			userRecordUpdateRequest.skeletalMuscle(),
			userRecordUpdateRequest.fatPercentage(),
			userRecordUpdateRequest.bmi()
		);
	}

	@Transactional
	public void upsertBodyInfoToUserRecord(
		Long userId,
		LocalDate recordDate,
		UserRecordUpdateRequest userRecordUpdateRequest
	) {
		userRecordRepository.findByRecordDateAndUser_UserId(recordDate, userId)
			.ifPresentOrElse(
				userRecord -> userRecord.updateBodyInfo(
					userRecordUpdateRequest.weight(),
					userRecordUpdateRequest.skeletalMuscle(),
					userRecordUpdateRequest.fatPercentage(),
					userRecordUpdateRequest.bmi()
				),
				() -> {
					UserRecord newUserRecord = buildNewUserRecord(userId, recordDate, userRecordUpdateRequest);
					userRecordRepository.save(newUserRecord);
				}
			);
	}

	@Transactional(readOnly = true)
	public UserRecordResponse getUserRecordsByUserAndPeriod(Long userId, Integer year, Integer month) {
		LocalDate startDate = LocalDate.of(year, month, 1);
		LocalDate endDate = YearMonth.of(year, month).atEndOfMonth();

		List<UserRecord> userRecords =
			userRecordRepository.findByUser_UserIdAndRecordDateBetweenWithCompletedRoutines(
				userId, startDate, endDate
			);

		List<RecordResponse> records = userRecords.stream()
			.map(record -> new RecordResponse(
				record.getUserRecordId(),
				record.getRecordDate(),
				!record.getCompletedRoutines().isEmpty()
			))
			.collect(Collectors.toList());

		return new UserRecordResponse(records);
	}

	@Transactional(readOnly = true)
	public List<BodyInfoChartResponse> getBodyInfoChart(Long userId, String type) {
		List<String> validTypes = List.of("weight", "skeletalMuscle", "fatPercentage", "bmi");
		if (!validTypes.contains(type)) {
			throw new InvalidInputException(ErrorMessage.INVALID_VALUE);
		}
		Pageable pageable = PageRequest.of(0, 100);
		List<UserRecord> userRecords = userRecordRepository.findUserRecordByTypeAndUserId(
			userId, type, pageable
		);

		Map<String, Function<UserRecord, Double>> typeToValueExtractor = Map.of(
			"weight", UserRecord::getWeight,
			"skeletalMuscle", UserRecord::getSkeletalMuscle,
			"fatPercentage", UserRecord::getFatPercentage,
			"bmi", UserRecord::getBmi
		);

		Function<UserRecord, Double> valueExtractor = typeToValueExtractor.get(type);

		return userRecords.stream()
			.map(record ->
				new BodyInfoChartResponse(record.getRecordDate(), valueExtractor.apply(record)))
			.collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public UserRecordDetailResponse getUserRecordDetail(Long userId, Long recordId) {
		UserRecord userRecord = userRecordRepository.findById(recordId)
			.orElseThrow(() -> new NotFoundException(ErrorMessage.NOT_FOUND_RECORD));

		if (!userRecord.getUser().getUserId().equals(userId)) {
			throw new AccessDeniedException();
		}
		List<CompletedRoutine> completedRoutines
			= completedRoutineRepository.findAllWithDetailsByUserRecordId(recordId);

		List<RecordsDto> records = mapToRecordsDto(completedRoutines);

		return new UserRecordDetailResponse(
			userRecord.getUserRecordId(),
			userRecord.getWeight(),
			userRecord.getSkeletalMuscle(),
			userRecord.getFatPercentage(),
			userRecord.getBmi(),
			records
		);
	}

	@Transactional(readOnly = true)
	public UserRecordDetailV2Response getUserRecordDetailV2(Long userId, LocalDate recordDate) {
		return userRecordRepository.findByRecordDateAndUser_UserId(recordDate, userId)
			.map(userRecord -> {
				List<CompletedRoutineDto> completedRoutines = completedRoutineRepository
					.findAllWithDetailsByUserIdAndRecordDate(userId, recordDate)
					.stream()
					.map(CompletedRoutineDto::from)
					.collect(Collectors.toList());

				return buildUserRecordDetailResponse(userRecord, completedRoutines);
			})
			.orElseGet(() -> new UserRecordDetailV2Response(
				null, null, null, null, null,
				Collections.emptyList()
			));
	}

	private UserRecordDetailV2Response buildUserRecordDetailResponse(
		UserRecord userRecord,
		List<CompletedRoutineDto> completedRoutines
	) {
		return new UserRecordDetailV2Response(
			userRecord.getUserRecordId(),
			userRecord.getWeight(),
			userRecord.getSkeletalMuscle(),
			userRecord.getFatPercentage(),
			userRecord.getBmi(),
			completedRoutines
		);
	}

	public UserRecord getUserRecordForCompleteRoutine(Long userId) {
		LocalDate recordDate = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).toLocalDate();
		return userRecordRepository.findByRecordDateAndUser_UserId(recordDate, userId)
			.orElseGet(() -> {
				User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
				UserRecord userRecord = UserRecord.builder()
					.user(user)
					.recordDate(recordDate)
					.build();
				return userRecordRepository.save(userRecord);
			});
	}

	private UserRecord buildNewUserRecord(
		Long userId,
		LocalDate recordDate,
		UserRecordUpdateRequest userRecordUpdateRequest
	) {
		User user = userRepository.findById(userId)
			.orElseThrow(UserNotFoundException::new);

		return UserRecord.builder()
			.user(user)
			.weight(userRecordUpdateRequest.weight())
			.skeletalMuscle(userRecordUpdateRequest.skeletalMuscle())
			.fatPercentage(userRecordUpdateRequest.fatPercentage())
			.bmi(userRecordUpdateRequest.bmi())
			.recordDate(recordDate)
			.build();
	}

	private LocalDate validateAndConvertToLocalDate(String date) {
		try {
			return LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
		} catch (DateTimeParseException e) {
			throw new InvalidInputException(ErrorMessage.INVALID_VALUE);
		}
	}

	private List<RecordsDto> mapToRecordsDto(List<CompletedRoutine> completedRoutines) {
		return completedRoutines.stream()
			.collect(Collectors.groupingBy(
				c -> Optional.ofNullable(c.getRoutine()),
				LinkedHashMap::new,
				Collectors.mapping(
					CompletedRoutineDto::from,
					Collectors.toList()
				)
			))
			.values().stream()
			.map(RecordsDto::from)
			.collect(Collectors.toList());
	}
}
