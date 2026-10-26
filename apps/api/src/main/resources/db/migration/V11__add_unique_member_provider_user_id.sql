CREATE UNIQUE INDEX uk_member_provider_user_id
    ON member (provider, provider_user_id)
    WHERE provider_user_id IS NOT NULL;
