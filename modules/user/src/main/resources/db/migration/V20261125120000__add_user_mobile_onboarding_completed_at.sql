-- 모바일 앱 온보딩(코치마크)을 완료한 시각을 저장합니다(MOM-1189). 완료 기록이 없는 사용자는 NULL로 유지합니다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS mobile_onboarding_completed_at TIMESTAMPTZ;
