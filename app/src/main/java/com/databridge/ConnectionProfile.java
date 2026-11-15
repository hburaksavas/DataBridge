package com.databridge;

public record ConnectionProfile(
    String id,
    String name,
    String folder,
    String environment,
    String username,
    String mode,
    String host,
    int port,
    String service,
    String database,
    String descriptor,
    String role,
    String color,
    boolean readOnly,
    boolean passwordSaved,
    String origin) {
  public ConnectionProfile normalized() {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("A connection name is required.");
    String m = mode == null ? "BASIC" : mode;
    if (!java.util.Set.of("BASIC", "TNS", "DESCRIPTOR").contains(m))
      throw new IllegalArgumentException("Invalid connection type.");
    String env = environment == null ? "TEST" : environment;
    if (!java.util.Set.of("PROD", "TEST", "UAT", "DEV").contains(env))
      throw new IllegalArgumentException("Invalid environment.");
    if (m.equals("BASIC")
        && ((host == null || !host.matches("[a-zA-Z0-9._:-]+"))
            || service == null
            || !service.matches("[a-zA-Z0-9._$#-]+")
            || port < 1
            || port > 65535))
      throw new IllegalArgumentException("Check the host, port and service name.");
    if (!m.equals("BASIC") && (descriptor == null || !descriptor.strip().startsWith("(")))
      throw new IllegalArgumentException(
          "TNS requires a resolved connection descriptor. Import tnsnames.ora first.");
    String r = role == null ? "NORMAL" : role;
    if (!java.util.Set.of("NORMAL", "SYSDBA", "SYSOPER").contains(r))
      throw new IllegalArgumentException("Invalid connection role.");
    return new ConnectionProfile(
        id == null || id.isBlank() ? java.util.UUID.randomUUID().toString() : id,
        name.strip(),
        folder == null ? "Genel" : folder,
        env,
        username == null ? "" : username,
        m,
        host == null ? "" : host,
        port,
        service == null ? "" : service,
        database == null ? "" : database,
        descriptor == null ? "" : descriptor,
        r,
        color == null ? "#386ce0" : color,
        env.equals("PROD") || readOnly,
        passwordSaved,
        origin == null ? "MANUAL" : origin);
  }

  public String jdbcUrl() {
    return "jdbc:oracle:thin:@"
        + (mode.equals("BASIC")
            ? "//"
                + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host)
                + ":"
                + port
                + "/"
                + service
            : descriptor);
  }
}
