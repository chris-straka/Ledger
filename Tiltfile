# Ledger inner loop: one app, one migration step, one Postgres.
# Observability resources arrive in Phase 8 under a clear group/profile.

docker_compose('./docker-compose.yaml')

# Rebuild the API image on Java sources; restart the resource on image change.
docker_build(
    'ledger-ledger-api',
    '.',
    dockerfile='./Dockerfile',
)

# Dependency order and useful links.
dc_resource('postgres', links=['http://127.0.0.1:5432'])
dc_resource('migrate', resource_deps=['postgres'])
dc_resource('ledger-api', resource_deps=['migrate'], links=[
    'http://127.0.0.1:8080/actuator/health',
])
