# Pneumatik frontend

React single-page app of [Pneumatik](../README.md): Vite + React 19 +
TypeScript, Tailwind 4 with shadcn/ui components, TanStack Query for data
fetching, and a typed API client generated from the backend's OpenAPI
contract.

## Running locally

Node 20+ required. The dev server proxies `/api` to the backend on
`http://localhost:8085` (start it with `./gradlew bootRun` in `../api`).

```sh
npm install
npm run dev        # → http://localhost:5173
```

## Scripts

| Command | What it does |
|---|---|
| `npm run dev` | dev server with HMR and `/api` proxy |
| `npm run build` | production build to `dist/` |
| `npm run typecheck` | `tsc -b --noEmit` |
| `npm run lint` | oxlint |
| `npm run generate:api` | regenerate `src/api/schema.d.ts` from `../api/src/main/resources/openapi.yaml` |

After any change to the OpenAPI spec, run `npm run generate:api` and commit
the result — CI fails when the spec and the generated client drift apart.

## Structure

| Path | Purpose |
|---|---|
| `src/api/` | generated schema types + configured `openapi-fetch` client (auth header, global 401 handling) |
| `src/lib/auth.ts` | token storage with subscribe hook |
| `src/components/ui/` | shadcn/ui primitives |
| `src/components/shared/` | app patterns: page header, form field, confirm dialog, empty state |
| `src/components/layout/` | authenticated shell (sidebar, mobile sheet, guard) |
| `src/pages/` | one file per screen |

Conventions: every mutation shows a toast; destructive actions confirm first;
422 field errors from the API render next to the input they concern; empty
states say what to do next. New top-level routes must also be added to the
security allowlist in `../api/grails-app/conf/application.groovy`.
