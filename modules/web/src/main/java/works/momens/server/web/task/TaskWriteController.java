package works.momens.server.web.task;

import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.project.taskupdate.TaskUpdateDetail;
import works.momens.server.web.dto.response.WebMessageResponse;
import works.momens.server.web.task.dto.request.CreateTaskUpdateRequest;
import works.momens.server.web.task.dto.request.CreateWebTaskRequest;
import works.momens.server.web.task.dto.request.UpdateWebTaskRequest;
import works.momens.server.web.task.dto.response.TaskUpdateListResponse.UpdateResponse;
import works.momens.server.web.task.dto.response.WebTaskResponse;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class TaskWriteController implements TaskWriteControllerDocs {
  private final TaskWriteService taskWriteService;
  private final TaskUpdateService taskUpdateService;

  @PostMapping(path = "/projects/{projectId}/tasks", version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  public WebTaskResponse create(
      @PathVariable UUID projectId,
      @RequestBody CreateWebTaskRequest request,
      Principal principal) {
    return WebTaskResponse.from(
        taskWriteService.create(
            projectId,
            CurrentUser.id(principal),
            request.title(),
            request.description(),
            request.status(),
            request.milestoneId(),
            request.priority(),
            request.assigneeId(),
            request.dueDate()));
  }

  @PatchMapping(path = "/tasks/{taskId}", version = "1")
  public WebTaskResponse update(
      @PathVariable UUID taskId, @RequestBody UpdateWebTaskRequest request, Principal principal) {
    return WebTaskResponse.from(
        taskWriteService.update(
            taskId,
            CurrentUser.id(principal),
            request.title(),
            request.titleSet(),
            request.description(),
            request.descriptionSet(),
            request.status(),
            request.statusSet(),
            request.priority(),
            request.prioritySet(),
            request.milestoneId(),
            request.milestoneIdSet(),
            request.assigneeId(),
            request.assigneeIdSet(),
            request.dueDate(),
            request.dueDateSet(),
            request.version()));
  }

  @DeleteMapping(path = "/tasks/{taskId}", version = "1")
  public WebMessageResponse delete(
      @PathVariable UUID taskId,
      @RequestParam(required = false) Long version,
      Principal principal) {
    taskWriteService.delete(taskId, CurrentUser.id(principal), version);
    return new WebMessageResponse("deleted");
  }

  @PostMapping(path = "/tasks/{taskId}/updates", version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  public UpdateResponse createUpdate(
      @PathVariable UUID taskId,
      @RequestBody CreateTaskUpdateRequest request,
      Principal principal) {
    TaskUpdateDetail update =
        taskUpdateService.create(
            taskId, CurrentUser.id(principal), request.body(), request.kind(), request.metadata());
    return UpdateResponse.from(update);
  }

  @DeleteMapping(path = "/tasks/{taskId}/updates/{updateId}", version = "1")
  public WebMessageResponse deleteUpdate(
      @PathVariable UUID taskId, @PathVariable UUID updateId, Principal principal) {
    taskUpdateService.delete(taskId, updateId, CurrentUser.id(principal));
    return new WebMessageResponse("deleted");
  }
}
