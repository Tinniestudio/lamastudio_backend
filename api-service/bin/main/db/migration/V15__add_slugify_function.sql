CREATE OR REPLACE FUNCTION slugify(val TEXT) RETURNS TEXT AS $$
BEGIN
    RETURN lower(
        regexp_replace(
            trim(regexp_replace(val, '[^a-zA-Z0-9\s\-]', '', 'g')),
            '\s+', '-', 'g'
        )
    );
END;
$$ LANGUAGE plpgsql IMMUTABLE;
