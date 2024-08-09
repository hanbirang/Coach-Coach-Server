package site.coach_coach.coach_coach_server.auth.jwt;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TokenFilter extends OncePerRequestFilter {
	private static final String ACCESS_TOKEN = "access_token";
	private static final String REFRESH_TOKEN = "refresh_token";

	private final TokenProvider tokenProvider;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		response.setCharacterEncoding("utf-8");

		String requestUri = request.getRequestURI();
		if (requestUri.equals("/api/v1/auth/login") || requestUri.equals("/api/v1/auth/signup")) {
			filterChain.doFilter(request, response);
			return;
		}

		String accessToken = tokenProvider.getCookieValue(request, ACCESS_TOKEN);
		String refreshToken = tokenProvider.getCookieValue(request, REFRESH_TOKEN);

		if ((accessToken != null && tokenProvider.validateAccessToken(accessToken))) {
			Authentication authentication = tokenProvider.getAuthentication(accessToken);
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} else if ((accessToken == null || !tokenProvider.validateAccessToken(accessToken)) && refreshToken != null) {
			boolean validRefreshToken = tokenProvider.validateRefreshToken(refreshToken);
			boolean isRefreshToken = tokenProvider.existsRefreshToken(refreshToken);

			if (validRefreshToken && isRefreshToken) {
				String newAccessToken = tokenProvider.regenerateAccessToken(refreshToken);

				clearCookie(response, ACCESS_TOKEN);

				Cookie newAccessTokenCookie = tokenProvider.createCookie(ACCESS_TOKEN, newAccessToken);
				response.addCookie(newAccessTokenCookie);

				Authentication authentication = tokenProvider.getAuthentication(newAccessToken);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}
		filterChain.doFilter(request, response);
	}

	private void clearCookie(HttpServletResponse response, String type) {
		Cookie oldCookie = new Cookie(type, null);
		oldCookie.setHttpOnly(true);
		oldCookie.setPath("/");
		oldCookie.setMaxAge(0);
		response.addCookie(oldCookie);
	}
}
