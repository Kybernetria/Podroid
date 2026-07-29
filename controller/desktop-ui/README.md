# Slint desktop UI

This crate is the external desktop presentation adapter over `controller-core`. It shows:

- preview host connection and identity;
- default VM lifecycle, backend, boot stage, uptime, and error;
- bounded Refresh, Start, and Stop actions; and
- pending-operation and failure feedback.

All service calls run on one worker with a one-item request queue. A shared in-flight guard permits at most one service/result callback until Slint applies it, bounding both command and event-loop work. Immutable validated presentation state is marshalled back through Slint's event loop. State-dependent policy disables unsafe or duplicate actions. Closing the window does not issue Stop.

The banner and connection status identify this build as an in-memory preview with no live phone connectivity. There are intentionally no QMP, path, shell, arbitrary-command, workload, or scheduling controls.
