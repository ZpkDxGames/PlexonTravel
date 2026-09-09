# Staging Gate

Run on Paper 26.2 / Java 25 with PlexonCore 2.0.0 and the current spawn/warp providers still present.

Required live checks: set/use spawn; separate hub and hub=spawn; missing destination; safe/unsafe locations; unloaded chunk; create/rename/delete warps; permission/public warp behavior; 50+ warp GUI pagination; warp deletion during warmup; `/back` after warp/death/external teleport/restart; movement vs rotation-only warmup; damage/cancelled damage; quit/death/world change; replacement request; fee success/insufficient/refund/bypass where enabled; migration scan/import idempotence with source unchanged; respawn modes if enabled; rollback; 10 simultaneous warmups; 100-warp GUI; 30-minute mixed soak; Spark profile.

Do not promote the prerelease to stable until these live-only gates pass.
