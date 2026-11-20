package works.momens.server.web.blocker;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.blocker.dto.request.CreateBlockerRequest;
import works.momens.server.web.blocker.dto.response.BlockerResponse;
import works.momens.server.web.dto.response.WebMessageResponse;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class BlockerWriteController implements BlockerWriteControllerDocs {
  private final BlockerWriteService blockerWriteService;

  @PostMapping(path = "/tasks/{taskId}/blockers", version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  public BlockerResponse create(
      @PathVariable UUID taskId,
      @Valid @RequestBody CreateBlockerRequest request,
      Principal principal) {
    return BlockerResponse.from(
        blockerWriteService.create(
            taskId, CurrentUser.id(principal), request.workspaceId(), request.description()));
  }

  @PatchMapping(path = "/blockers/{blockerId}/resolve", version = "1")
  public WebMessageResponse resolve(@PathVariable UUID blockerId, Principal principal) {
    blockerWriteService.resolve(blockerId, CurrentUser.id(principal));
    return new WebMessageResponse("resolved");
  }

  @DeleteMapping(path = "/blockers/{blockerId}", version = "1")
  public WebMessageResponse delete(@PathVariable UUID blockerId, Principal principal) {
    blockerWriteService.delete(blockerId, CurrentUser.id(principal));
    return new WebMessageResponse("deleted");
  }
}
