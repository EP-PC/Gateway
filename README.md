# Gateway

Único ponto de entrada da store: roteia `/auth/**`, `/accounts/**`, `/products/**`, `/orders/**`
e `/exchange/**` para os serviços internos e valida o cookie JWT chamando o Auth.

## Kubernetes

Manifestos em `k8s/k8s.yaml`: Deployment `gateway` + Service `LoadBalancer` 8080 (o único
Service exposto; os demais são `ClusterIP`).

### readinessProbe

O container tem um probe TCP na porta 8080:

```yaml
readinessProbe:
  tcpSocket:
    port: 8080
  initialDelaySeconds: 20
  periodSeconds: 5
  failureThreshold: 6
```

Sem probe, no rolling update o Kubernetes considerava o pod novo pronto assim que o container
subia, derrubava o antigo e a API respondia 503 durante os ~40 s de inicialização do Spring.
A 8080 (Netty) só abre no fim da inicialização do contexto, então o teste TCP indica "pronto"
sem actuator e sem mudar código. Falhar no probe não reinicia o pod: ele só fica fora do
Service até passar.

## CI/CD (Jenkins)

Estágios do `Jenkinsfile` (sem `Dependencies`: o gateway não tem repo de contrato):

1. `Build`: `mvn -B -DskipTests clean package`.
2. `Build & Push Image`: publica `pedrohmm1/gateway:latest` e `pedrohmm1/gateway:<BUILD_ID>`
   (linux/amd64 e linux/arm64) no Docker Hub.
3. `Deploy to EKS`: atualiza o Deployment `gateway` no cluster `eks-store` (us-east-2) para a
   imagem `pedrohmm1/gateway:<BUILD_ID>` que o estágio anterior acabou de publicar.

O deploy usa a credencial `aws-credential` do Jenkins. O estágio gera um kubeconfig temporário
no workspace (apagado ao sair, mesmo em caso de erro) e roda:

```bash
kubectl set image deployment/gateway gateway=pedrohmm1/gateway:<BUILD_ID>
kubectl rollout status deployment/gateway --timeout=300s
```

- Cluster desligado (`ResourceNotFoundException` no `describe-cluster`): avisa e pula o deploy,
  sem quebrar o build.
- Qualquer outro erro (credencial, permissão, Deployment inexistente, rollout que não termina
  em 5 min): o build falha.
- Graças ao `readinessProbe`, o `rollout status` só termina quando o pod novo aceita conexões,
  e o pod antigo continua atendendo até lá.

**Tags:** depois de um deploy pelo Jenkins, o cluster roda a tag `<BUILD_ID>`. O `k8s/k8s.yaml`
deste repo continua com a tag do commit (`7fda6bf`), que é a usada pelo `aws/up.ps1` do
store-pma ao recriar o cluster. Ou seja, um cluster recém-criado volta para a imagem do
manifesto até o próximo build no Jenkins.
