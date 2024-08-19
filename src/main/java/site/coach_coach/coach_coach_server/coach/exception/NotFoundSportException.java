package site.coach_coach.coach_coach_server.coach.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundSportException extends RuntimeException {
	public NotFoundSportException(String message) {
		super(message);
	}
}
