-- 태스크를 생성한 사용자를 저장하기 위한 컬럼입니다.
-- 기존 태스크에는 작성자 정보가 없으므로 nullable로 추가하며, 기존 데이터는 보정하지 않습니다.
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS creator_id UUID;
