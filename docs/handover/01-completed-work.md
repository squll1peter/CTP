# Completed Work

## Security and Login

- Login popup submits credentials using POST rather than GET query parameters.
- Confirmed server-side login handling exists in bundled util.jar LoginServlet (doGet and doPost are present).

## SSL and Browser Behavior

- HTTPS on 8443 is functioning for current validation flow.
- Browser-dependent probe traffic (especially from Chrome during untrusted-cert handling) was generating repeated "Attack detected" warnings from LoginServlet.
- Logging was tightened to suppress noisy false-positive warnings from org.rsna.servlets.LoginServlet while keeping broader RSNA logs intact.

## Configuration Defaults

- Server default for enableStageProfiling changed from yes to no in configuration templates.
- Runtime fallback default in org.rsna.ctp.Configuration was aligned to disabled profiling.

## Build and Packaging

- Packaging/build validation completed after changes using ant jar and ant installer.
- Runtime tree under build/CTP refreshed with updated resources and config defaults.
