ALTER TABLE region
    DROP COLUMN si,
    DROP COLUMN gun,
    DROP COLUMN gu,
    ADD COLUMN sido_code VARCHAR(5) NOT NULL,
    ADD COLUMN sigungu_code VARCHAR(5) NOT NULL,
    ADD COLUMN sido_name VARCHAR(10) NOT NULL,
    ADD COLUMN sigungu_name VARCHAR(10) NOT NULL;

COMMENT ON COLUMN region.sido_code IS '시도 코드';
COMMENT ON COLUMN region.sigungu_code IS '시군구 코드';
COMMENT ON COLUMN region.sido_name IS '시도 이름';
COMMENT ON COLUMN region.sigungu_name IS '시군구 이름';
