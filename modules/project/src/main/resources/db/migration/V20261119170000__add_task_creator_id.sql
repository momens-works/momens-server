-- 태스크를 생성한 사용자를 저장하기 위한 컬럼입니다.
-- 신규 서버 외부에서 생성한 태스크(컬럼 추가 이전 데이터 및 신규 서버 전환 전 레거시 writer로 생성한 데이터)에는 작성자 정보가 없으므로 nullable 컬럼으로 추가하며, 기존 데이터는 보정하지 않습니다.
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS creator_id UUID REFERENCES users(id) ON DELETE SET NULL;
