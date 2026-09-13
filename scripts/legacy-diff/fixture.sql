-- 레거시 차등 비교 하네스 픽스처(MOM-0877).
--
-- 레거시 momens-api 000001_init.sql 과 신규 서버의 users/workspace 마이그레이션은 이 세 테이블에
-- 대해 동일한 DDL 입니다. 그래서 같은 INSERT 가 양쪽 DB 에서 그대로 돕니다.
--
-- id 와 시각을 고정하는 이유: 값까지 문자 그대로 같아야 diff 가 곧 계약 차이가 됩니다. 고정하지
-- 않으면 매 실행마다 UUID·타임스탬프가 달라 정규화가 필요하고, 정규화는 진짜 차이를 함께 지웁니다.
--
-- ⚠️  파괴적입니다. 아래 TRUNCATE 는 CASCADE 로 projects, tasks, user_identities 등 users 를
--     참조하는 모든 테이블까지 비웁니다. run.sh 는 이 파일을 compose 가 띄운 일회용 컨테이너에만
--     적용하므로 안전하지만, psql 명령을 그대로 복사해 로컬 개발 DB(docker-compose.yml 의
--     momens-postgres-data 볼륨)에 돌리면 그 데이터가 사라집니다. 대상 DB 를 반드시 확인하세요.

BEGIN;

-- TRUNCATE ... CASCADE 는 딸려 비워지는 테이블마다 NOTICE 를 냅니다. write 케이스마다 이 파일을
-- 다시 적용하므로(MOM-0882) 그대로 두면 NOTICE 가 차등 비교 출력을 덮습니다.
SET LOCAL client_min_messages TO WARNING;

TRUNCATE workspace_members, workspaces, users CASCADE;
-- memory 계열은 따로 비웁니다. 레거시에서는 workspaces CASCADE 로 함께 지워지지만, 신규 서버의
-- 미러는 다른 모듈 테이블로 나가는 FK 를 두지 않아 CASCADE 가 닿지 않습니다. 명시하지 않으면 write
-- 케이스마다 이 파일을 다시 적용할 때 신규 쪽만 이전 행이 남아 PK 충돌로 멈춥니다.
TRUNCATE review_actions, confirmed_memories, memory_candidates CASCADE;

INSERT INTO users (id, email, name, avatar_url, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000001', 'owner@momens.works',    '홍길동', 'https://cdn.momens.works/avatars/owner.png', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000002', 'member@momens.works',   '김철수', NULL,                                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000003', 'stranger@momens.works', '박영희', NULL,                                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000004', 'nobody@momens.works',   '이민수', NULL,                                        '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z');

-- created_at 을 서로 다르게 둬 목록 정렬(내림차순)을 검증합니다.
-- alpha 는 description 이 NULL 이라 레거시 omitempty 생략 동작을 드러냅니다.
INSERT INTO workspaces (id, name, slug, description, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000011', 'Alpha', 'ws-alpha', NULL,                 '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000012', 'Beta',  'ws-beta',  '제품팀 워크스페이스', '2026-02-01T00:00:00Z', '2026-02-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000013', 'Gamma', 'ws-gamma', '외부 워크스페이스',   '2026-03-01T00:00:00Z', '2026-03-01T00:00:00Z');

-- owner 는 alpha·beta 의 멤버이고 gamma 는 아닙니다(403 경로).
-- nobody 는 어디에도 속하지 않습니다(빈 목록 경로).
INSERT INTO workspace_members (workspace_id, user_id, role, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000011', '00000000-0000-4000-8000-000000000001', 'owner',  '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000012', '00000000-0000-4000-8000-000000000001', 'member', '2026-02-01T00:00:00Z', '2026-02-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000012', '00000000-0000-4000-8000-000000000002', 'owner',  '2026-02-01T00:00:00Z', '2026-02-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000013', '00000000-0000-4000-8000-000000000003', 'owner',  '2026-03-01T00:00:00Z', '2026-03-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000012', '00000000-0000-4000-8000-000000000003', 'admin',  '2026-02-15T00:00:00Z', '2026-02-15T00:00:00Z');

-- H023은 레거시의 projects.progress와 DATE 직렬화가 신규 계약과 의도적으로 다른지 golden으로 고정합니다.
INSERT INTO projects (id, workspace_id, label, name, owner_id, target_date, progress, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000021', '00000000-0000-4000-8000-000000000012', 'PRJ-0001', 'Beta 프로젝트', '00000000-0000-4000-8000-000000000002', '2026-07-31', 40, '2026-02-02T00:00:00Z', '2026-02-02T00:00:00Z');

-- milestone은 owner 행을 두지 않아 snapshot의 빈 owner_user_ids 직렬화 차이를 함께 대조합니다.
INSERT INTO milestones (id, project_id, name, progress, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000022', '00000000-0000-4000-8000-000000000021', 'Beta 마일스톤', 40, '2026-02-03T00:00:00Z', '2026-02-03T00:00:00Z');

-- H040은 워크스페이스별 연결 목록과 생성 시각 내림차순 정렬을 대조합니다.
INSERT INTO source_connections (id, workspace_id, source_type, status, external_workspace_id, external_workspace_name, connected_by_user_id, connected_at, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000031', '00000000-0000-4000-8000-000000000012', 'GITHUB', 'ACTIVE', 'momens-org', 'Momens', '00000000-0000-4000-8000-000000000001', '2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z', '2026-04-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000032', '00000000-0000-4000-8000-000000000012', 'SLACK',  'ACTIVE', 'T-momens',   'momens',  '00000000-0000-4000-8000-000000000001', '2026-04-02T00:00:00Z', '2026-04-02T00:00:00Z', '2026-04-02T00:00:00Z');

-- H096은 검증 후 응답 필드 구성과 verified_* 갱신을 대조합니다.
INSERT INTO source_refs (id, workspace_id, source_type, source_object_type, source_object_id, source_url, title, snippet, text, visibility, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000041', '00000000-0000-4000-8000-000000000012', 'figma', 'FILE_COMMENT', 'obj-1', 'https://figma.com/file/abc', '권한 요청 화면 v2', '설명 문구 변경', '수집한 원문 전체', 'WORKSPACE', '2026-04-03T00:00:00Z', '2026-04-03T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000042', '00000000-0000-4000-8000-000000000013', 'figma', 'FILE_COMMENT', 'obj-2', 'https://figma.com/file/def', '다른 워크스페이스 화면', '다른 워크스페이스 설명', '다른 워크스페이스 원문', 'WORKSPACE', '2026-04-04T00:00:00Z', '2026-04-04T00:00:00Z');

-- H067부터 H071까지 태스크를 대상으로 하므로 프로젝트 021 아래에 태스크 한 건을 생성합니다.
INSERT INTO tasks (id, workspace_id, project_id, label, title, description, status, priority, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000081', '00000000-0000-4000-8000-000000000012', '00000000-0000-4000-8000-000000000021', 'MOM-0001', '태스크 목록 조회 API', '목록 endpoint를 구현합니다', 'todo', 'medium', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z');

-- 해제 케이스인 H068과 H071은 요청 전에 연결이 존재해야 합니다.
-- 케이스마다 픽스처를 다시 적용하므로 앞선 케이스에서 생성한 연결에 의존할 수 없습니다.
-- 091은 메모리 062와의 연결이고, 092는 source_ref 041과의 연결입니다.
-- 메모리 061은 연결하지 않아 해제할 연결이 없는 케이스를 다룹니다.
INSERT INTO entity_relations (id, workspace_id, from_entity_type, from_entity_id, relation_type, to_entity_type, to_entity_id, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000091', '00000000-0000-4000-8000-000000000012', 'TASK', '00000000-0000-4000-8000-000000000081', 'LINKED_TO', 'MEMORY',        '00000000-0000-4000-8000-000000000062', '2026-06-02T00:00:00Z', '2026-06-02T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000092', '00000000-0000-4000-8000-000000000012', 'TASK', '00000000-0000-4000-8000-000000000081', 'LINKED_TO', 'SOURCE_OBJECT', '00000000-0000-4000-8000-000000000041', '2026-06-03T00:00:00Z', '2026-06-03T00:00:00Z');

-- memory 후보 리뷰(H084~H088)와 해결(H093) 케이스용 시드입니다.
--
-- 라벨을 반드시 명시합니다. 레거시에는 trg_memory_candidates_label·trg_confirmed_memories_label 이
-- 있어 label 이 NULL 이면 next_workspace_label() 이 자동 발급하지만, 신규 서버 미러에는 트리거가
-- 없습니다(라벨은 LabelAllocator 가 명시 발급). 라벨을 비우면 픽스처 적용 직후부터 양쪽 DB 가
-- 달라지고, 그 차이가 모든 memory 케이스에 섞여 들어옵니다.
--
-- 051 은 제목만 있는 후보, 052 는 선택 필드가 모두 채워진 후보입니다. 052 의 metadata 로 "확정 시
-- 후보 metadata 를 옮기지 않는다"는 레거시 동작을 대조합니다.
-- 053 은 이미 리뷰된 후보라 상태 위반(409) 경로를 만듭니다.
-- 054 는 gamma 소속이라 owner 가 멤버가 아닌 워크스페이스입니다(403 경로).
INSERT INTO memory_candidates (id, workspace_id, label, candidate_type, title, summary, body, confidence, importance, status, source_ref_ids, related_entity_ids, proposed_by, reviewed_at, reviewed_by_user_id, metadata, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000051', '00000000-0000-4000-8000-000000000012', 'SUG-0001', 'DECISION',      '결제 재시도는 3회로 고정한다', NULL,     NULL,     NULL, NULL, 'PROPOSED',  NULL, NULL, 'CURATOR', NULL, NULL, NULL, '2026-05-01T00:00:00Z', '2026-05-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000052', '00000000-0000-4000-8000-000000000012', 'SUG-0002', 'OPEN_QUESTION', '환불 기준을 누가 정하는가',   '요약 문장', '본문 전체', 0.8,  0.6,  'PROPOSED',  '{00000000-0000-4000-8000-000000000041}', '{00000000-0000-4000-8000-000000000021}', 'CURATOR', NULL, NULL, '{"extractor": "curator-v2"}', '2026-05-02T00:00:00Z', '2026-05-02T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000053', '00000000-0000-4000-8000-000000000012', 'SUG-0003', 'DECISION',      '이미 확정된 후보',            NULL,     NULL,     NULL, NULL, 'CONFIRMED', NULL, NULL, 'CURATOR', '2026-05-03T00:00:00Z', '00000000-0000-4000-8000-000000000002', NULL, '2026-05-03T00:00:00Z', '2026-05-03T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000054', '00000000-0000-4000-8000-000000000013', 'SUG-0001', 'DECISION',      '외부 워크스페이스 후보',      NULL,     NULL,     NULL, NULL, 'PROPOSED',  NULL, NULL, 'CURATOR', NULL, NULL, NULL, '2026-05-04T00:00:00Z', '2026-05-04T00:00:00Z');

-- 061 은 병합 대상이자 해결 대상, 062 는 해결하는 쪽입니다. 063 은 gamma 소속이라 교차 워크스페이스
-- 차단 경로를 만듭니다.
INSERT INTO confirmed_memories (id, workspace_id, label, memory_type, title, summary, body, status, source_ref_ids, related_entity_ids, confirmed_by_user_id, confirmed_at, metadata, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000061', '00000000-0000-4000-8000-000000000012', 'MEM-0001', 'DECISION', '결제 재시도 정책',        '기존 요약', NULL, 'ACTIVE', NULL, NULL, '00000000-0000-4000-8000-000000000002', '2026-05-05T00:00:00Z', NULL, '2026-05-05T00:00:00Z', '2026-05-05T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000062', '00000000-0000-4000-8000-000000000012', 'MEM-0002', 'DECISION', '결제 재시도 정책 개정',   NULL,       NULL, 'ACTIVE', NULL, NULL, '00000000-0000-4000-8000-000000000002', '2026-05-06T00:00:00Z', NULL, '2026-05-06T00:00:00Z', '2026-05-06T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000063', '00000000-0000-4000-8000-000000000013', 'MEM-0001', 'DECISION', '외부 워크스페이스 메모리', NULL,      NULL, 'ACTIVE', NULL, NULL, '00000000-0000-4000-8000-000000000003', '2026-05-07T00:00:00Z', NULL, '2026-05-07T00:00:00Z', '2026-05-07T00:00:00Z');

-- 064 는 소프트 삭제된 메모리입니다. 레거시가 남긴 이런 행이 prod 에 있을 수 있고, 신규 서버가
-- DELETED 를 새로 만들지 않는 것(H094 는 이관하지 않습니다, MOM-0901)과 기존 행을 레거시와 같게
-- 다루는 것은 다른 문제라 픽스처에 둡니다. 레거시는 모든 조회·잠금에 deleted_at IS NULL 을 걸어
-- 이 행을 없는 것으로 봅니다(memory/repository.go).
--
-- 라벨이 MEM-0003 인 것은 부분 UNIQUE 인덱스가 label IS NOT NULL 만 보고 deleted_at 은 보지 않기
-- 때문입니다. 소프트 삭제된 행도 라벨을 계속 점유하므로 발급 카운터를 4 로 올려 확정 케이스가
-- MEM-0004 를 받게 합니다. 카운터가 3 이면 양쪽 다 이 인덱스에 걸립니다.
INSERT INTO confirmed_memories (id, workspace_id, label, memory_type, title, summary, body, status, source_ref_ids, related_entity_ids, confirmed_by_user_id, confirmed_at, metadata, created_at, updated_at, deleted_at) VALUES
  ('00000000-0000-4000-8000-000000000064', '00000000-0000-4000-8000-000000000012', 'MEM-0003', 'DECISION', '지워진 메모리', NULL, NULL, 'DELETED', NULL, NULL, '00000000-0000-4000-8000-000000000002', '2026-05-08T00:00:00Z', NULL, '2026-05-08T00:00:00Z', '2026-05-09T00:00:00Z', '2026-05-09T00:00:00Z');

-- 라벨 발급 카운터를 위 시드 다음 번호로 맞춥니다. 비워 두면 확정이 이미 쓰인 MEM-0001 을 다시
-- 발급해 레거시의 부분 UNIQUE 인덱스에 걸립니다. 레거시 next_workspace_label() 과 신규
-- LabelAllocator 가 같은 테이블에 같은 방식(직전 값 발급 후 1 증가)으로 접근하므로 양쪽이 같은
-- 라벨을 냅니다. 확정 케이스는 이 값으로 MEM-0004 를 받습니다.
-- H037은 발급된 라벨이 기존 카운터의 다음 값인지 검증하므로 PRJ 카운터 행을 미리 생성합니다.
-- 이 행이 없으면 양쪽 서버가 모두 PRJ-0001을 발급하므로 기존 카운터를 읽었는지 확인할 수 없습니다.
INSERT INTO workspace_label_sequences (workspace_id, label_prefix, next_value, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000012', 'MEM', 4, '2026-05-08T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000012', 'PRJ', 2, '2026-05-08T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000012', 'SUG', 4, '2026-05-03T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000013', 'MEM', 2, '2026-05-07T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000013', 'SUG', 2, '2026-05-04T00:00:00Z');
-- 초대 상태에 따라 응답과 허용되는 동작이 달라지므로 네 가지 상태의 데이터를 모두 추가합니다. 051은 대기 중인 초대이며, 052는 대기 중이지만 만료 시각이 지난
-- 초대입니다. 052를 통해 목록 응답에서 상태가 expired로 계산되는지 확인합니다. 053은 폐기된 초대이고, 054는 수락된 초대이므로 재발송과 폐기 요청이 모두
-- 거부되어야 합니다.
--
-- 각 이메일은 서로 다른 사용자를 가리킵니다. 051은 어느 워크스페이스에도 속하지 않은 사용자이므로 초대 수락 경로 전체를 검증할 수 있습니다. 054는 이미 수락된
-- 초대이므로 더 이상 사용자를 조회하지 않아 해당 이메일에 대응하는 사용자 행이 없어도 됩니다.
--
-- 토큰 해시는 미리 정한 문자열의 SHA-256 값으로 고정합니다. 초대 수락 케이스에서 해당 문자열을 그대로 전달해야 하므로 재현 가능한 값이 필요합니다.
INSERT INTO workspace_invitations (id, workspace_id, email, role, inviter_id, token_hash, status, expires_at, accepted_at, revoked_at, last_sent_at, created_at, updated_at) VALUES
  ('00000000-0000-4000-8000-000000000071', '00000000-0000-4000-8000-000000000011', 'nobody@momens.works',   'member', '00000000-0000-4000-8000-000000000001', '4bd54704a50ba0404c280691fd61393cff752ea34eee498ea854dc9287c17b49', 'pending',  '2099-01-01T00:00:00Z', NULL,                   NULL,                   '2026-05-01T00:00:00Z', '2026-05-01T00:00:00Z', '2026-05-01T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000072', '00000000-0000-4000-8000-000000000011', 'member@momens.works',   'admin',  '00000000-0000-4000-8000-000000000001', 'bfab9869e075b272cb46ef9b1fc6d9ee58a8dee51f63f31884b8039446e9d334', 'pending',  '2020-01-01T00:00:00Z', NULL,                   NULL,                   '2026-05-02T00:00:00Z', '2026-05-02T00:00:00Z', '2026-05-02T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000073', '00000000-0000-4000-8000-000000000011', 'stranger@momens.works', 'member', '00000000-0000-4000-8000-000000000001', '908a89d749b286e3686884008ff99db58fe3de7696700dd340f3c6be3624c576', 'revoked',  '2099-01-01T00:00:00Z', NULL,                   '2026-05-04T00:00:00Z', '2026-05-03T00:00:00Z', '2026-05-03T00:00:00Z', '2026-05-04T00:00:00Z'),
  ('00000000-0000-4000-8000-000000000074', '00000000-0000-4000-8000-000000000011', 'joined@momens.works',   'member', '00000000-0000-4000-8000-000000000001', 'd11dac997d16358ec8ef51f6909e6beb2af9998e65b9f963803f45c07a811511', 'accepted', '2099-01-01T00:00:00Z', '2026-05-05T00:00:00Z', NULL,                   '2026-05-04T00:00:00Z', '2026-05-04T00:00:00Z', '2026-05-05T00:00:00Z');

COMMIT;
