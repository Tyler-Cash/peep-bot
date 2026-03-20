## 2026-03-24 - [Broken Authorization in Event Updates]
**Vulnerability:** Any authenticated user could update any event by sending a PATCH request to `/api/event`, as the controller lacked creator/admin checks.
**Learning:** The `updateEvent` method was missing an authorization check that was present in other sensitive operations like `cancelEvent` and `removeAttendee`.
**Prevention:** Always verify that every state-changing endpoint has appropriate ownership or role-based access control (RBAC) checks.
