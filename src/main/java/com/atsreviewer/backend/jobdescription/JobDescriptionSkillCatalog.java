package com.atsreviewer.backend.jobdescription;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.atsreviewer.backend.engine.JdClassifier;
import com.atsreviewer.backend.engine.SkillMatcher;
import com.atsreviewer.backend.engine.model.JdSkill;

@Service
public class JobDescriptionSkillCatalog {
	private final JdbcClient jdbcClient;

	public JobDescriptionSkillCatalog(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<JdSkill> classify(String jobDescription) {
		CatalogData catalog = loadCatalog();
		return new JdClassifier(new SkillMatcher(catalog.aliases()), catalog.skillNames()).classify(jobDescription);
	}

	public SkillMatcher skillMatcher() {
		return new SkillMatcher(loadCatalog().aliases());
	}

	private CatalogData loadCatalog() {
		Map<String, Integer> aliases = new HashMap<>();
		Map<Integer, String> skillNames = new HashMap<>();
		List<CatalogEntry> entries = jdbcClient.sql("""
				SELECT s.id, s.canonical_name, sa.alias
				FROM skills s
				LEFT JOIN skill_aliases sa ON sa.skill_id = s.id
				ORDER BY s.id, sa.alias
				""")
			.query((resultSet, rowNumber) -> new CatalogEntry(
					resultSet.getInt("id"),
					resultSet.getString("canonical_name"),
					resultSet.getString("alias")))
			.list();

		for (CatalogEntry entry : entries) {
			String existingName = skillNames.putIfAbsent(entry.skillId(), entry.canonicalName());
			if (existingName != null && !existingName.equals(entry.canonicalName())) {
				throw new IllegalStateException(
						"Skill ID has more than one canonical name: " + entry.skillId());
			}
			addAlias(aliases, entry.canonicalName(), entry.skillId());
			if (entry.alias() != null) {
				addAlias(aliases, entry.alias(), entry.skillId());
			}
		}

		if (skillNames.isEmpty()) {
			throw new JobDescriptionException(
					HttpStatus.SERVICE_UNAVAILABLE,
					"SKILL_CATALOG_EMPTY",
					"The skill catalogue is empty, so this job description cannot be classified.");
		}
		return new CatalogData(Map.copyOf(aliases), Map.copyOf(skillNames));
	}

	private void addAlias(Map<String, Integer> aliases, String alias, int skillId) {
		Integer existingSkillId = aliases.putIfAbsent(alias, skillId);
		if (existingSkillId != null && existingSkillId != skillId) {
			throw new IllegalStateException("Skill alias maps to multiple skills: " + alias);
		}
	}

	private record CatalogEntry(int skillId, String canonicalName, String alias) {
	}

	private record CatalogData(Map<String, Integer> aliases, Map<Integer, String> skillNames) {
	}
}
