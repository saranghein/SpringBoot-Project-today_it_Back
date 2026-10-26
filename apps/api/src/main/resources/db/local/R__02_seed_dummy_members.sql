INSERT INTO member (
    member_id,
    preferred_region,
    email,
    password,
    provider,
    nickname,
    preferred_theme,
    is_active
)
SELECT
    seed.member_id,
    region.region_id,
    seed.email,
    seed.password,
    'LOCAL',
    seed.nickname,
    NULL,
    TRUE
FROM (
    VALUES
        (
            '00000000-0000-0000-0000-000000000001',
            'user@todayit.local',
            '$2a$10$Yx34R9g2N8toLxLOseLVwePc.DqDjTwAgLpu6GelYT4rYR7sOvtPm',
            '더미사용자'
        ),
        (
            '00000000-0000-0000-0000-000000000002',
            'admin@todayit.local',
            '$2a$10$Yx34R9g2N8toLxLOseLVwePc.DqDjTwAgLpu6GelYT4rYR7sOvtPm',
            '더미관리자'
        ),
        (
            '00000000-0000-0000-0000-000000000003',
            'dev@todayit.local',
            '$2a$10$Yx34R9g2N8toLxLOseLVwePc.DqDjTwAgLpu6GelYT4rYR7sOvtPm',
            '더미개발자'
        )
) AS seed(member_id, email, password, nickname)
CROSS JOIN region
WHERE region.sido_code = '11'
  AND region.sigungu_code = '110'
  AND NOT EXISTS (
      SELECT 1
      FROM member
      WHERE member.member_id = seed.member_id
         OR member.email = seed.email
  );

UPDATE member
SET password = '$2a$10$Yx34R9g2N8toLxLOseLVwePc.DqDjTwAgLpu6GelYT4rYR7sOvtPm'
WHERE email IN (
    'user@todayit.local',
    'admin@todayit.local',
    'dev@todayit.local'
);
