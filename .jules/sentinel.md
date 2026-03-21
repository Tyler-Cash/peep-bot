## 2026-03-24 - [BOLA Protection in Event Updates]
**Vulnerability:** Broken Object Level Authorization (BOLA) in `EventController.updateEvent`. Any authenticated user could modify any event by its UUID.
**Learning:** Initial implementation focused on functional requirements and assumed the frontend would be the only client. Missing server-side authorization checks on resource-specific actions (like PATCH) created a significant security gap.
**Prevention:** Always verify that the authenticated user is either the resource owner (creator) or has an administrative role before allowing modifications to sensitive resources. Use `ResponseStatusException(HttpStatus.FORBIDDEN)` as a standard for authorization failures.
