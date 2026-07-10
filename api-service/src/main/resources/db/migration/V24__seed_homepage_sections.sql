INSERT INTO homepage_sections (title, section_type, display_order, is_active) VALUES
    ('Trending Now',  'TRENDING',     1, true),
    ('Featured',      'FEATURED',     2, true),
    ('New Releases',  'NEW_RELEASES', 3, true),
    ('Coming Soon',   'COMING_SOON',  4, true)
ON CONFLICT (section_type) DO NOTHING;
