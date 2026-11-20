package works.momens.server.web.decision;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import works.momens.server.common.api.CurrentUser;
import works.momens.server.web.decision.dto.request.CreateDecisionRequest;
import works.momens.server.web.decision.dto.response.WebDecisionResponse;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class DecisionController implements DecisionControllerDocs {
  private final DecisionService decisionService;

  @PostMapping(path = "/projects/{projectId}/decisions", version = "1")
  @ResponseStatus(HttpStatus.CREATED)
  public WebDecisionResponse createDecision(
      @PathVariable UUID projectId,
      @Valid @RequestBody CreateDecisionRequest request,
      Principal principal) {
    return WebDecisionResponse.from(
        decisionService.create(projectId, CurrentUser.id(principal), request));
  }
}
