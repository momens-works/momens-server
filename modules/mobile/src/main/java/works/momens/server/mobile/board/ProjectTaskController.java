package works.momens.server.mobile.board;

import jakarta.validation.Valid;
import java.security.Principal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.common.api.FieldValidationException;
import works.momens.server.mobile.board.dto.request.CreateTaskRequest;
import works.momens.server.mobile.board.dto.response.TaskBoardResponse;
import works.momens.server.mobile.board.dto.response.TaskCreateResponse;
import works.momens.server.project.task.TaskPriority;
import works.momens.server.project.task.TaskRole;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
class ProjectTaskController implements ProjectTaskControllerDocs {

  private final ProjectTaskService projectTaskService;

  @Override
  @GetMapping(path = "/projects/{projectId}/tasks", version = "1")
  public TaskBoardResponse getBoard(
      @PathVariable UUID projectId,
      @RequestParam(name = "roles", required = false) List<TaskRole> roles,
      @RequestParam(name = "priorities", required = false) List<TaskPriority> priorities,
      @RequestParam(name = "due_date", required = false) @DateTimeFormat(iso = ISO.DATE)
          LocalDate dueDate,
      @RequestParam(name = "assignee_id", required = false) UUID assigneeId,
      @RequestParam(name = "unassigned", defaultValue = "false") boolean unassigned,
      Principal principal) {
    if (assigneeId != null && unassigned) {
      throw FieldValidationException.forField("unassigned");
    }
    MobileTaskFilter filter =
        new MobileTaskFilter(
            roles == null ? null : new HashSet<>(roles),
            priorities == null ? null : new HashSet<>(priorities),
            dueDate,
            assigneeId,
            unassigned);
    return TaskBoardResponse.from(
        projectTaskService.getBoard(projectId, CurrentUser.id(principal), filter));
  }

  @Override
  @PostMapping(path = "/projects/{projectId}/tasks", version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  public TaskCreateResponse createTask(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateTaskRequest request,
      Principal principal) {
    if (request.roles() == null && request.role() == null) {
      throw FieldValidationException.forField("roles");
    }
    Set<TaskRole> roles =
        request.roles() != null ? Set.copyOf(request.roles()) : Set.of(request.role());
    return TaskCreateResponse.from(
        projectTaskService.createTask(
            projectId,
            CurrentUser.id(principal),
            request.title(),
            roles,
            request.priority(),
            request.dueDate()),
        roles);
  }
}
