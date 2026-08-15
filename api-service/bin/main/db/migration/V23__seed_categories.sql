-- Slugs are auto-set by trg_category_slug trigger
INSERT INTO categories (name, display_order) VALUES
    ('Action',      1),
    ('Drama',       2),
    ('Comedy',      3),
    ('Thriller',    4),
    ('Sci-Fi',      5),
    ('Horror',      6),
    ('Romance',     7),
    ('Documentary', 8),
    ('Kids',        9),
    ('Animation',  10),
    ('Fantasy',    11),
    ('Crime',      12),
    ('Sports',     13),
    ('Sermons',    14),
    ('Reality',    15),
    ('History',    16),
    ('Music',      17),
    ('Travel',     18)
ON CONFLICT (name) DO NOTHING;
