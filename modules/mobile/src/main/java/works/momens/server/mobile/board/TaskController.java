package works.momens.server.mobile.board;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.mobile.board.dto.request.ToggleChecklistItemRequest;
import works.momens.server.mobile.board.dto.request.UpdateTaskRequest;
import works.momens.server.mobile.board.dto.response.ChecklistToggleResponse;
import works.momens.server.mobile.board.dto.response.TaskDetailResponse;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
class TaskController implements TaskControllerDocs {

  private final ProjectTaskService projectTaskService;

  @Override
  @GetMapping(path = "/tasks/{taskId}", version = "1")
  public TaskDetailResponse getTaskDetail(@PathVariable UUID taskId, Principal principal) {
    return TaskDetailResponse.from(
        projectTaskService.getTaskDetail(taskId, CurrentUser.id(principal)));
  }

  @Override
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PatchMapping(path = "/tasks/{taskId}", version = "1")
  public void updateTask(
      @PathVariable UUID taskId,
      @Valid @RequestBody UpdateTaskRequest request,
      Principal principal) {
    List<ChecklistEdit> checklistItems =
        request.checklistItems().stream()
            .map(item -> new ChecklistEdit(item.id(), item.title(), item.completed()))
            .toList();
    projectTaskService.updateTask(
        taskId,
        CurrentUser.id(principal),
        request.title(),
        request.role(),
        request.assigneeId(),
        request.priority(),
        request.status(),
        request.purpose(),
        checklistItems);
  }

  @Override
  @PatchMapping(path = "/tasks/{taskId}/checklist-items/{itemId}", version = "1")
  public ChecklistToggleResponse toggleChecklistItem(
      @PathVariable UUID taskId,
      @PathVariable UUID itemId,
      @Valid @RequestBody ToggleChecklistItemRequest request,
      Principal principal) {
    MobileTaskDetail updated =
        projectTaskService.toggleChecklistItem(
            taskId, CurrentUser.id(principal), itemId, request.completed());
    return ChecklistToggleResponse.from(updated, itemId);
  }
}
