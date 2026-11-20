package com.atsreviewer.backend.guest;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PurgeJobs {
	private final GuestSessionService guestSessionService;

	public PurgeJobs(GuestSessionService guestSessionService) {
		this.guestSessionService = guestSessionService;
	}

	@Scheduled(cron = "0 0 * * * *")
	public void purgeExpiredGuests() {
		guestSessionService.purgeExpiredGuests();
	}
}
