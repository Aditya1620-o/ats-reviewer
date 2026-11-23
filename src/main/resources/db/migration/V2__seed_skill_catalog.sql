INSERT INTO skills (canonical_name, category) VALUES
    ('Java', 'programming_language'),
    ('Python', 'programming_language'),
    ('AWS', 'cloud'),
    ('Docker', 'devops_tool');

INSERT INTO skill_aliases (alias, skill_id) VALUES
    ('Python 3', (SELECT id FROM skills WHERE canonical_name = 'Python')),
    ('Amazon Web Services', (SELECT id FROM skills WHERE canonical_name = 'AWS')),
    ('Docker Engine', (SELECT id FROM skills WHERE canonical_name = 'Docker'));
