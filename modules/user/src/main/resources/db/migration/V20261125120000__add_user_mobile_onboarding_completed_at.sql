-- 모바일 앱 온보딩(코치마크)을 완료하거나 건너뛴 시각을 저장합니다(MOM-1189). 완료 기록이 없는 사용자는 NULL로 유지합니다.
-- users는 레거시에서 생성한 테이블이므로 IF NOT EXISTS를 사용하여 기존 컬럼과의 충돌을 방지합니다.
ALTER TABLE users ADD COLUMN IF NOT EXISTS mobile_onboarding_completed_at TIMESTAMPTZ;
