-- source_refs.source_object_type과 source_object_id의 local 기본값을 제거합니다(MOM-0918).
--
-- 운영 환경의 두 컬럼은 NOT NULL이며 기본값이 없습니다. V20260821090200에서 해당 테이블을 읽기
-- 전용으로 사용하던 시점에 추가한 'UNKNOWN'과 '' 기본값 때문에, 두 컬럼을 명시하지 않은 INSERT가
-- local과 test 환경에서는 성공하지만 운영 환경에서는 실패했습니다.
--
-- DROP DEFAULT는 기본값이 없는 컬럼에 실행해도 실패하지 않으므로 운영 환경의 스키마에는 변경이
-- 없습니다.
ALTER TABLE source_refs
    ALTER COLUMN source_object_type DROP DEFAULT,
    ALTER COLUMN source_object_id DROP DEFAULT;
