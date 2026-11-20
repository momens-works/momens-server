package works.momens.server.project.task;

public enum TaskOrigin {
  MANUAL("manual"),
  SIGNAL("signal");

  private final String value;

  TaskOrigin(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
