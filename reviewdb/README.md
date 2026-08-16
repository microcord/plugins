# Manticord ReviewDB Plugin

Standalone ReviewDB plugin for Manticord. It provides user and server review pages, paginated review
fetching, review create/update, votes with the vertical score/up/down layout, reporting, blocks,
opt-out, appeals, and OAuth authorization.

Build with the shared Manticord lock:

```powershell
bash ../manticordrebuild/scripts/build.sh -p . installDebug --offline
```

Enable and approve **ReviewDB** in Manticord Settings > Plugins. The user profile ReviewDB section and
User/Guild context menus open review pages.
