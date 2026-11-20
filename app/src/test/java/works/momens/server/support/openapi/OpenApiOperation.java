package works.momens.server.support.openapi;

/** 스냅샷에 선언된 엔드포인트 하나를 나타냅니다. 경로, HTTP 메서드, {@code operationId}를 포함합니다. */
record OpenApiOperation(String path, String method, String operationId) {}
