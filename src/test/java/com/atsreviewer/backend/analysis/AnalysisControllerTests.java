package com.atsreviewer.backend.analysis;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.atsreviewer.backend.analysis.AnalysisDtos.CreateRequest;
import com.atsreviewer.backend.guest.GuestSessionService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@org.springframework.boot.test.context.SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class AnalysisControllerTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private JsonMapper objectMapper;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Test
	void createsQueuedAnalysisAndReturnsItsCurrentStatus() throws Exception {
		Fixture owner = createFixture();
		UUID analysisId = createAnalysis(owner);

		mockMvc.perform(withOwner(get("/v1/analyses/{id}", analysisId), owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(analysisId.toString()))
			.andExpect(jsonPath("$.status").value("queued"))
			.andExpect(jsonPath("$.engine_version").value("1.0.0"))
			.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void returnsCompletedScoreAndBreakdown() throws Exception {
		Fixture owner = createFixture();
		UUID analysisId = createAnalysis(owner);
		jdbcClient.sql("""
				UPDATE analyses
				SET status = 'done', score = 82.50,
				    started_at = :startedAt, finished_at = :finishedAt
				WHERE id = :id
				""")
			.param("startedAt", Timestamp.from(Instant.now()))
			.param("finishedAt", Timestamp.from(Instant.now()))
			.param("id", analysisId)
			.update();
		int javaSkillId = jdbcClient.sql("SELECT id FROM skills WHERE canonical_name = 'Java'")
				.query((resultSet, rowNumber) -> resultSet.getInt(1))
				.single();
		jdbcClient.sql("""
				INSERT INTO analysis_items (analysis_id, skill_id, weight, status, found_in, points)
				VALUES (:analysisId, :skillId, 3.0, 'used', 'EXPERIENCE', 3.0)
				""")
			.param("analysisId", analysisId)
			.param("skillId", javaSkillId)
			.update();

		mockMvc.perform(withOwner(get("/v1/analyses/{id}", analysisId), owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("done"))
			.andExpect(jsonPath("$.score").value(82.5))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].skill_name").value("Java"))
			.andExpect(jsonPath("$.items[0].status").value("used"))
			.andExpect(jsonPath("$.items[0].found_in").value("EXPERIENCE"));
	}

	@Test
	void onlyTheOwnerCanCreateOrReadAnAnalysis() throws Exception {
		Fixture owner = createFixture();
		Fixture otherOwner = createFixture();
		UUID analysisId = createAnalysis(owner);

		mockMvc.perform(get("/v1/analyses/{id}", analysisId)
				.cookie(otherOwner.cookie()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("ANALYSIS_NOT_FOUND"));

		mockMvc.perform(post("/v1/analyses")
				.cookie(otherOwner.cookie())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request(owner.resumeVersionId(), otherOwner.jobDescriptionId())))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("ANALYSIS_INPUT_NOT_FOUND"));
	}

	@Test
	void authenticatedUserCanCreateAndReadAnAnalysis() throws Exception {
		Fixture owner = createAuthenticatedFixture();
		UUID analysisId = createAnalysis(owner);

		mockMvc.perform(withOwner(get("/v1/analyses/{id}", analysisId), owner))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(analysisId.toString()))
			.andExpect(jsonPath("$.status").value("queued"));
	}

	@Test
	void requiresAnOwnerAndBothInputIds() throws Exception {
		mockMvc.perform(post("/v1/analyses")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("OWNER_REQUIRED"));

		Fixture owner = createFixture();
		mockMvc.perform(post("/v1/analyses")
				.cookie(owner.cookie())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("RESUME_VERSION_REQUIRED"));

		mockMvc.perform(post("/v1/analyses")
				.cookie(owner.cookie())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request(owner.resumeVersionId(), null)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("JOB_DESCRIPTION_REQUIRED"));
	}

	@Test
	void duplicateCreateReturnsTheExistingAnalysis() throws Exception {
		Fixture owner = createFixture();
		UUID firstId = createAnalysis(owner);
		UUID retryId = createAnalysis(owner);

		assertEquals(firstId, retryId);
	}

	private UUID createAnalysis(Fixture owner) throws Exception {
		MvcResult result = mockMvc.perform(withOwner(post("/v1/analyses"), owner)
				.contentType(MediaType.APPLICATION_JSON)
				.content(request(owner.resumeVersionId(), owner.jobDescriptionId())))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("queued"))
			.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return UUID.fromString(body.path("id").stringValue());
	}

	private String request(UUID resumeVersionId, UUID jobDescriptionId) throws Exception {
		return objectMapper.writeValueAsString(new CreateRequest(resumeVersionId, jobDescriptionId));
	}

	private Fixture createFixture() throws Exception {
		MvcResult result = mockMvc.perform(post("/v1/guest-sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"captcha_token\":\"test\"}"))
			.andExpect(status().isCreated())
			.andReturn();
		String guestCookie = result.getResponse().getCookie(GuestSessionService.COOKIE_NAME).getValue();
		UUID guestSessionId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
				.path("guest_session_id")
				.stringValue());

		InputIds inputs = createInputs(null, guestSessionId);
		return new Fixture(new MockCookie(GuestSessionService.COOKIE_NAME, guestCookie), null,
				inputs.resumeVersionId(), inputs.jobDescriptionId());
	}

	private Fixture createAuthenticatedFixture() throws Exception {
		String signupResponse = mockMvc.perform(post("/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + UUID.randomUUID() + "@example.com\","
						+ "\"password\":\"long-enough-password\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String accessToken = objectMapper.readTree(signupResponse)
				.path("access_token")
				.stringValue();
		UUID userId = UUID.fromString(jwtDecoder.decode(accessToken).getSubject());
		InputIds inputs = createInputs(userId, null);
		return new Fixture(null, accessToken, inputs.resumeVersionId(), inputs.jobDescriptionId());
	}

	private InputIds createInputs(UUID userId, UUID guestSessionId) {
		UUID resumeId = UUID.randomUUID();
		UUID resumeVersionId = UUID.randomUUID();
		UUID jobDescriptionId = UUID.randomUUID();
		Timestamp createdAt = Timestamp.from(Instant.now());
		if (userId != null) {
			jdbcClient.sql("""
					INSERT INTO resumes (id, user_id, title, created_at)
					VALUES (:id, :ownerId, 'Test resume', :createdAt)
					""")
				.param("id", resumeId)
				.param("ownerId", userId)
				.param("createdAt", createdAt)
				.update();
		} else {
			jdbcClient.sql("""
					INSERT INTO resumes (id, guest_session_id, title, created_at)
					VALUES (:id, :ownerId, 'Test resume', :createdAt)
					""")
				.param("id", resumeId)
				.param("ownerId", guestSessionId)
				.param("createdAt", createdAt)
				.update();
		}
		jdbcClient.sql("""
				INSERT INTO resume_versions
					(id, resume_id, version_no, storage_file_id, sha256, mime, size_bytes)
				VALUES (:id, :resumeId, 1, :storageKey, :sha256, :mime, 1)
				""")
			.param("id", resumeVersionId)
			.param("resumeId", resumeId)
			.param("storageKey", resumeVersionId + ".pdf")
			.param("sha256", UUID.randomUUID().toString().replace("-", "").repeat(2))
			.param("mime", "application/pdf")
			.update();
		if (userId != null) {
			jdbcClient.sql("""
					INSERT INTO job_descriptions (id, user_id, title, raw_text, created_at)
					VALUES (:id, :ownerId, 'Test JD', :rawText, :createdAt)
					""")
				.param("id", jobDescriptionId)
				.param("ownerId", userId)
				.param("rawText", "Backend engineer role. ".repeat(12))
				.param("createdAt", createdAt)
				.update();
		} else {
			jdbcClient.sql("""
					INSERT INTO job_descriptions (id, guest_session_id, title, raw_text, created_at)
					VALUES (:id, :ownerId, 'Test JD', :rawText, :createdAt)
					""")
				.param("id", jobDescriptionId)
				.param("ownerId", guestSessionId)
				.param("rawText", "Backend engineer role. ".repeat(12))
				.param("createdAt", createdAt)
				.update();
		}
		return new InputIds(resumeVersionId, jobDescriptionId);
	}

	private MockHttpServletRequestBuilder withOwner(
			MockHttpServletRequestBuilder request,
			Fixture owner) {
		if (owner.accessToken() != null) {
			return request.header("Authorization", "Bearer " + owner.accessToken());
		}
		return request.cookie(owner.cookie());
	}

	private record InputIds(UUID resumeVersionId, UUID jobDescriptionId) {
	}

	private record Fixture(MockCookie cookie, String accessToken, UUID resumeVersionId, UUID jobDescriptionId) {
	}
}
