-- 값이 실제로 바뀌는 요청. 양쪽 모두 updated_at 을 갱신하지만 값은 각자의 벽시계라 문자 그대로는
-- 비교할 수 없습니다. cases.tsv 의 scrub=time 이 등장 순서 자리표시자로 바꿔주므로, 양쪽 다
-- "created_at 과 updated_at 이 다르다"(Time_1, Time_2)로 수렴해 비교가 성립합니다.
SELECT name, slug, description, created_at, updated_at
FROM workspaces
WHERE id = '00000000-0000-4000-8000-000000000011';
