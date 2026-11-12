package works.momens.server.mcp.tools.internal;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import works.momens.server.project.core.ProjectDetail;
import works.momens.server.project.milestone.MilestoneDetail;
import works.momens.server.user.UserProfile;

final class McpToolReferences {
  private McpToolReferences() {}

  static Optional<UUID> uuid(String reference) {
    try {
      UUID id = UUID.fromString(reference);
      return id.toString().equalsIgnoreCase(reference) ? Optional.of(id) : Optional.empty();
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
  }

  static void requireProject(List<ProjectDetail> projects, UUID projectId) {
    if (projects.stream().noneMatch(project -> project.id().equals(projectId))) {
      throw new McpToolInputException("Resource not found in this workspace.");
    }
  }

  static ProjectDetail resolveProject(List<ProjectDetail> projects, String reference) {
    Optional<UUID> id = uuid(reference);
    if (id.isPresent()) {
      return projects.stream()
          .filter(project -> project.id().equals(id.get()))
          .findFirst()
          .orElseThrow(
              () -> new McpToolInputException("No project with this id in this workspace"));
    }
    Optional<ProjectDetail> labelled =
        projects.stream()
            .filter(project -> reference.equalsIgnoreCase(project.label()))
            .findFirst();
    if (labelled.isPresent()) {
      return labelled.get();
    }
    String lower = reference.toLowerCase(Locale.ROOT);
    List<ProjectDetail> exact =
        projects.stream()
            .filter(project -> project.name().strip().equalsIgnoreCase(reference))
            .toList();
    List<ProjectDetail> matches =
        exact.isEmpty()
            ? projects.stream()
                .filter(project -> project.name().strip().toLowerCase(Locale.ROOT).contains(lower))
                .toList()
            : exact;
    if (matches.size() == 1) {
      return matches.getFirst();
    }
    throw new McpToolInputException(
        matches.isEmpty()
            ? "No project matching this reference. Use list_projects."
            : "This reference matches several projects — use the PRJ-label");
  }

  static MilestoneDetail resolveMilestone(List<MilestoneDetail> milestones, String reference) {
    Optional<UUID> id = uuid(reference);
    if (id.isPresent()) {
      return milestones.stream()
          .filter(milestone -> milestone.id().equals(id.get()))
          .findFirst()
          .orElseThrow(() ->
              new McpToolInputException("No milestone with this id in this workspace or project"));
    }
    List<MilestoneDetail> exact =
        milestones.stream()
            .filter(milestone -> milestone.name().strip().equalsIgnoreCase(reference))
            .toList();
    List<MilestoneDetail> matches =
        exact.isEmpty()
            ? milestones.stream()
                .filter(milestone ->
                    milestone
                        .name()
                        .strip()
                        .toLowerCase(Locale.ROOT)
                        .contains(reference.toLowerCase(Locale.ROOT)))
                .toList()
            : exact;
    if (matches.size() == 1) {
      return matches.getFirst();
    }
    throw new McpToolInputException(
        matches.isEmpty()
            ? "No milestone matching this reference. Use list_milestones."
            : "This reference matches several milestones — use the id");
  }

  static UUID resolveAssignee(List<UserProfile> members, String reference) {
    Optional<UUID> id = uuid(reference);
    if (id.isPresent()) {
      return members.stream()
          .filter(member -> member.id().equals(id.get()))
          .map(UserProfile::id)
          .findFirst()
          .orElseThrow(() -> new McpToolInputException("No member with this id in this workspace"));
    }
    Optional<UserProfile> email =
        members.stream()
            .filter(member -> member.email().strip().equalsIgnoreCase(reference))
            .findFirst();
    if (email.isPresent()) {
      return email.get().id();
    }
    List<UserProfile> exact =
        members.stream()
            .filter(member -> member.name().strip().equalsIgnoreCase(reference))
            .toList();
    List<UserProfile> partial =
        members.stream()
            .filter(member ->
                !member.name().isBlank()
                    && !member.name().strip().equalsIgnoreCase(reference)
                    && member
                        .name()
                        .strip()
                        .toLowerCase(Locale.ROOT)
                        .contains(reference.toLowerCase(Locale.ROOT)))
            .toList();
    if (exact.size() == 1) {
      return exact.getFirst().id();
    }
    if (exact.size() > 1 || partial.size() > 1) {
      throw new McpToolInputException("This reference matches several members — use their email");
    }
    if (partial.size() == 1) {
      return partial.getFirst().id();
    }
    throw new McpToolInputException("No member matching this reference. Use list_members.");
  }
}
