-- users.email은 사용자 식별자가 아니라 표시와 동기화를 위한 값이므로 UNIQUE 제약을 제거합니다(MOM-0836, ADR-0016).
-- 운영의 제약은 레거시 000001_init.sql의 컬럼 UNIQUE 선언이 만든 users_email_key입니다.
-- 이름이 다르면 제거되지 않은 채 통과하지 않도록 IF EXISTS를 붙이지 않습니다.
ALTER TABLE users DROP CONSTRAINT users_email_key;
