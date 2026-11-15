ALTER TABLE place ADD COLUMN visitor_info JSONB;
COMMENT ON COLUMN place.visitor_info IS '편의시설·서비스·운영 안내';
