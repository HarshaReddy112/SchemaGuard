# SchemaGuard Frontend

This frontend provides the migration-risk workflow for the SchemaGuard analyzer.

## Local setup

1. Install dependencies:
   npm install
2. Start the Vite development server:
   npm run dev
3. Ensure the backend is running on http://localhost:8080 before analyzing migrations.

## Supported workflow

- Paste an old and new SQL schema into the two editors.
- Click Analyze Migration to call the schema diff API.
- Review risk summary, high-risk changes, and migration-plan guidance.
- If the impact-aware API is unavailable, the UI falls back to the base diff endpoint without failing the workflow.

## Notes

- The app expects a Spring Boot backend from this repository.
- The analysis remains conservative and additive: unsupported SQL is explicitly rejected by the backend, and the UI surfaces the response rather than guessing at migration safety.
