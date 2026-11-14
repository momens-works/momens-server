package works.momens.server.project.task.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import works.momens.server.project.task.TaskRole;

/**
 * {@code task_roles.role}에 저장되는 소문자 문자열과 {@link TaskRole} enum을 상호 변환합니다. CHECK 제약 조건에서 허용하는 네 가지 값
 * 외의 값이 조회되면 예외를 발생시킵니다.
 */
@Converter
class TaskRoleAttributeConverter implements AttributeConverter<TaskRole, String> {

  @Override
  public String convertToDatabaseColumn(TaskRole role) {
    return role.value();
  }

  @Override
  public TaskRole convertToEntityAttribute(String value) {
    return TaskRole.from(value).orElseThrow();
  }
}
