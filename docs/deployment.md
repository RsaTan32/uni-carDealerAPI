# GitHub, GHCR, Argo CD and k3s

This is a deployment template, not an already applied VPS configuration. Commands
below target a cluster only when you explicitly run them with your own kubectl
context. No real credentials or private keys belong here. Deployment-specific
repository/image names are configured separately from secret values.

## 1. Publication safety gate

The existing local Git history contains a deleted PostgreSQL Secret manifest.
Deleting a file or adding it to `.gitignore` does not remove its old commits.
The repository owner confirmed that the previously committed passwords have been
rotated and chose to retain the old commits. Keep those old values permanently
invalid and do not reuse them elsewhere. History cleanup is optional after proper
revocation, not a substitute for rotation. No history rewriting is performed.

Review both the working tree and history with a redacting secret scanner before
publishing. Also inspect SQL dumps, local env files, private keys, wrapper settings,
and build artifacts. A public repo does not make old secrets safe. The local
corporate Maven settings file is ignored; the wrapper downloads Maven from Central.

On GitHub, only grant collaborators write access deliberately. Public readers can
clone/fork and propose pull requests, not push to your branch. Protect the default
branch with required CI checks; do not expose write tokens to fork pull requests.
The workflow uses `pull_request`, not `pull_request_target`, and cannot write Git.
Choose a reuse license before advertising the repository as a reusable template.

## 2. CI and image publication

Push the sanitized project root (the directory with `pom.xml`) to GitHub. No
GitHub token needs to be stored in this repository. The built-in `GITHUB_TOKEN`
gets `packages: write` only for the publication job.

The workflow tests/packages with Java 21, renders both Kustomize configurations,
builds the runtime image, and tests the actual production container against a
fresh PostgreSQL with ephemeral JWT keys. Only then is it pushed to
`ghcr.io/<owner>/<repository>:<commit-sha>`. Pull requests do not publish images.

Set the GHCR package visibility to public if the cluster should pull it without
registry credentials. A public source repository does not automatically make
its GHCR package public. Otherwise configure an imagePullSecret in `car-rental`
for both the Deployment and migration Job, without committing its contents.
The current image build uses the runner's linux/amd64 platform. Check `uname -m`
on the VPS before deployment; ARM servers require a corresponding image build.

No mutable `latest` tag is deployed. Copy the full successful commit tag into
`k8s/backend/kustomization.yaml`; optionally pin the digest instead. Manifest-only
pushes do not rebuild the image. This is deliberate manual promotion: CI cannot
silently rewrite production manifests or publish a pull request's image.

## 3. Verify the cluster

Run read-only checks on the VPS or from a trusted kubeconfig:

```shell
kubectl get nodes
kubectl get ingressclass
kubectl -n kube-system get pods --show-labels
kubectl -n postgres get services,pods
kubectl api-resources --api-group=traefik.io
```

The template assumes the existing `postgres` Service in namespace `postgres`,
database `cardealer`, PostgreSQL pod label `app=postgres`, and k3s Traefik/CoreDNS
labels. Adjust the ConfigMap and NetworkPolicy if these differ. NetworkPolicy
requires enforcement by the cluster's network plugin; verify it rather than
assuming it works. Keep SSH, the Kubernetes API and Argo CD restricted.

`k8s/postgres/postgres-service.yaml` now describes ClusterIP instead of NodePort.
Applying it closes the old external database port; prepare SSH/kubectl forwarding
before doing that. It is not automatically included in the backend Argo application.
Review any other consumers of that database before changing its network access.
Use a host/provider firewall as well, and restrict database ingress separately.

## 4. Namespace, configuration and secrets

Create only namespace/configuration first, not the serving Deployment:

```shell
kubectl apply -f k8s/backend/namespace.yaml
kubectl -n car-rental apply -f k8s/backend/configmap.yaml
```

Secrets are namespace-local. A Secret in `postgres` cannot be referenced directly
by a pod in `car-rental`. Create separate runtime and migration database credentials
in the backend namespace. The runtime role needs DML/sequence permissions, not
superuser/DDL rights; the migration role needs schema and `btree_gist` permissions.
Provision those PostgreSQL roles deliberately before the following Bash commands:

```shell
for secret in car-rental-db car-rental-migration-db; do
  printf 'Credentials for %s\n' "$secret"
  read -r -p 'Database username: ' DB_USER
  read -r -s -p 'Database password: ' DB_PASS
  printf '\n'
  kubectl -n car-rental create secret generic "$secret" \
    --from-literal=username="$DB_USER" --from-literal=password="$DB_PASS" \
    --dry-run=client -o yaml | kubectl apply -f -
  unset DB_USER DB_PASS
done
```

Type passwords directly in your terminal, never in chat or committed commands.
Avoid running credential commands in logged/shared terminal sessions. For GitOps
secret management later, use an encrypted/external secret system. A base64-encoded
plain Kubernetes Secret is not encrypted Git storage.

Generate a persistent RSA key pair on a trusted machine outside tracked files:

```shell
umask 077
mkdir -p .secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out .secrets/privateKey.pem
openssl pkey -in .secrets/privateKey.pem -pubout -out .secrets/publicKey.pem
kubectl -n car-rental create secret generic car-rental-jwt \
  --from-file=privateKey.pem=.secrets/privateKey.pem \
  --from-file=publicKey.pem=.secrets/publicKey.pem \
  --dry-run=client -o yaml | kubectl apply -f -
```

Back up the keys securely. Do not generate a different pair on every release.
The pod reads them through a read-only volume; `fsGroup` provides access for UID 185. Key/config/Secret changes need a controlled rollout; environment variables
already loaded by a running pod do not update automatically.

For the first administrator, create the optional migration-only bootstrap Secret:

```shell
read -r -p 'Initial admin email: ' ADMIN_EMAIL
read -r -s -p 'Initial admin password (12+ characters): ' ADMIN_PASSWORD
printf '\n'
kubectl -n car-rental create secret generic car-rental-bootstrap \
  --from-literal=APP_AUTH_BOOTSTRAP_ADMIN_EMAIL="$ADMIN_EMAIL" \
  --from-literal=APP_AUTH_BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -
unset ADMIN_EMAIL ADMIN_PASSWORD
```

The regular Deployment does not reference this bootstrap Secret. Remove it after
successful account initialization, and do not leave it available for later jobs.

## 5. Backup and migrate before serving

Take a PostgreSQL backup to protected storage outside this public repository.
Verify restore procedures. Neither a PVC nor a container image is a DB backup.
Inspect active reservation overlaps using the README query before V2.

Set IMAGE to the exact image published by successful CI. This command creates a
one-off Job and never changes the serving Deployment:

```shell
IMAGE=ghcr.io/replace-owner/replace-repository:replace-with-commit-sha
kubectl set env --local -f k8s/jobs/migrate.yaml \
  QUARKUS_FLYWAY_BASELINE_ON_MIGRATE=true QUARKUS_FLYWAY_BASELINE_VERSION=0 -o yaml |
  kubectl set image --local -f - migration="$IMAGE" -o yaml |
  kubectl create -f -
kubectl -n car-rental wait --for=condition=complete job/car-rental-migrate --timeout=300s
kubectl -n car-rental logs job/car-rental-migrate
```

The image's `--migrate` mode runs Flyway during startup, validates the schema,
optionally bootstraps the administrator, then exits. V0 creates a fresh rental
schema. A nonempty existing schema is baselined at 0, skipping V0; V1/V2 add
accounts and overlap protection. Do not enable baseline-on-migrate indiscriminately
on arbitrary databases. Existing baselines/history must be reviewed first.

The Job has no automatic retries and is not an Argo hook. A failure stops this
release procedure; inspect it rather than starting the serving release anyway.
The completed Job is retained for one hour. For subsequent releases, remove only
a completed old Job before reusing its name, and normally omit the baseline flags.
Never delete a running migration Job merely to retry it. Normal pods keep
Flyway migrate-at-start disabled and use the restricted runtime DB role.

```shell
kubectl -n car-rental delete secret car-rental-bootstrap
```

## 6. Argo CD and domainless access

Replace the owner/repository/image tag in `k8s/backend/kustomization.yaml` and the
repo URL/default branch in `k8s/argocd/application.yaml`. Commit those reviewed
manifests, then create the Application using the existing Argo CD installation:

```shell
kubectl apply -f k8s/argocd/application.yaml
kubectl -n car-rental rollout status deployment/car-rental-api --timeout=300s
kubectl -n car-rental port-forward service/car-rental-api 8080:8080
```

The last command exposes only localhost by default. From a workstation with
trusted cluster access, use `http://localhost:8080/api/cars`. If it runs on the
VPS, use an SSH tunnel to reach that VPS-local port. Do not add `--address 0.0.0.0`
or temporarily expose credentials through a public HTTP NodePort.

The app manages only `k8s/backend`, not the existing PostgreSQL/PVC. Automatic
pruning and cascading deletion are deliberately not enabled. Further Argo project
RBAC restrictions can limit allowed repositories/namespaces for this application.

## 7. A real .dev domain and HTTPS

`.dev` is HSTS-preloaded: browsers require HTTPS. The placeholder `api.example.dev`
must be replaced with a domain you own. Before enabling the HTTPS overlay:

1. Set DNS A (and only a working IPv6 AAAA, if applicable) to the VPS.
2. Verify Traefik, cert-manager and a real `letsencrypt-production` ClusterIssuer.
3. Ensure ACME challenge connectivity and the needed 80/443 firewall rules.
4. Replace every host in `k8s/overlays/https/ingress.yaml`.
5. Change the Argo Application source path to `k8s/overlays/https` and commit.
6. Confirm Certificate readiness and successful HTTPS before sending credentials.

The overlay routes `/api` only; `/q/health` is not publicly routed. More-specific
login/register routes use a Traefik middleware limiting each source IP to an average
5 requests/minute with burst 5. Tune this for expected traffic/shared NAT. Verify
real client IP handling if another proxy/CDN is in front; never trust arbitrary
forwarded headers. The middleware requires the `traefik.io/v1alpha1` CRD.

## 8. Releases, rollback and remaining operations

For each application release: successful CI/image test, backup and reviewed
migration if needed, then commit the promoted image tag/digest. Argo syncs the
new pod. There is one replica by default; adjust memory/CPU/replicas for the VPS.
Image rollback is a Git revert of the image reference, but it does not undo a
database migration. Review backwards compatibility before rollback.

Still your operational responsibility: provider/host firewall, PostgreSQL access
restrictions, Argo/kubeconfig RBAC, backup monitoring and restore drills, security
updates, log retention/alerting, and protected signing keys. These templates alone
do not make an uninspected VPS production-ready or highly available.

Local release-image verification (Docker required):

```powershell
.\mvnw.cmd verify
docker build -f src/main/docker/Dockerfile.jvm -t car-rental-api:release-check .
.\mvnw.cmd "-Drelease.image=car-rental-api:release-check" "-Dtest=ProductionImageTest" test
```
