// Escrowrail infrastructure: one Container Apps environment + ACR +
// prod and staging backends. Deploy:
//   az group create -n escrowrail-rg -l swedencentral
//   az deployment group create -g escrowrail-rg -f infra/main.bicep \
//     --parameters image=<img>@<digest> dbUrl=... dbUser=... dbPassword=... \
//     jwtSecret=... fieldEncryptionKey=... githubWebhookSecret=... \
//     logisticsWebhookSecret=... resendApiKey=...
@description('Deployment region — student subs allow swedencentral')
param location string = 'swedencentral'

@description('Container image incl. registry, e.g. escrowrailacr.azurecr.io/escrowrail-backend@sha256:...')
param image string

@secure()
param dbUrl string
@secure()
param dbUser string
@secure()
param dbPassword string
@secure()
param jwtSecret string
@secure()
param fieldEncryptionKey string
@secure()
param githubWebhookSecret string
@secure()
param logisticsWebhookSecret string
@secure()
param resendApiKey string

param corsOrigins string = 'https://escrowrail.vercel.app'

var acrName = 'escrowrailacr'
var envName = 'escrowrail-env'

resource acr 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: acrName
  location: location
  sku: { name: 'Basic' }
  properties: { adminUserEnabled: true }
}

var secrets = [
  { name: 'dbpw', value: dbPassword }
  { name: 'dburl', value: dbUrl }
  { name: 'dbuser', value: dbUser }
  { name: 'jwt', value: jwtSecret }
  { name: 'enckey', value: fieldEncryptionKey }
  { name: 'ghsec', value: githubWebhookSecret }
  { name: 'losec', value: logisticsWebhookSecret }
  { name: 'resend', value: resendApiKey }
  { name: 'acrpw', value: acr.listCredentials().passwords[0].value }
]

var sharedEnv = [
  { name: 'SPRING_DATASOURCE_URL', secretRef: 'dburl' }
  { name: 'SPRING_DATASOURCE_USERNAME', secretRef: 'dbuser' }
  { name: 'SPRING_DATASOURCE_PASSWORD', secretRef: 'dbpw' }
  { name: 'JWT_SECRET', secretRef: 'jwt' }
  { name: 'ESCROWRAIL_FIELD_ENCRYPTION_KEY', secretRef: 'enckey' }
  { name: 'ESCROWRAIL_GITHUB_WEBHOOK_SECRET', secretRef: 'ghsec' }
  { name: 'ESCROWRAIL_LOGISTICS_WEBHOOK_SECRET', secretRef: 'losec' }
  { name: 'ESCROWRAIL_MAIL_RESEND_API_KEY', secretRef: 'resend' }
  { name: 'PORT', value: '8080' }
]

var registryConf = [{
  server: acr.properties.loginServer
  username: acr.listCredentials().username
  passwordSecretRef: 'acrpw'
}]

resource env 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: envName
  location: location
  properties: {}
}

resource prodApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'escrowrail-api'
  location: location
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      ingress: { external: true, targetPort: 8080 }
      secrets: secrets
      registries: registryConf
    }
    template: {
      containers: [{
        name: 'api'
        image: image
        resources: { cpu: '0.5', memory: '1Gi' }
        env: concat(sharedEnv, [
          { name: 'SPRING_PROFILES_ACTIVE', value: 'prod,demo' }
          { name: 'ESCROWRAIL_CORS_ORIGINS', value: corsOrigins }
        ])
      }]
      scale: { minReplicas: 0, maxReplicas: 1 }
    }
  }
}

resource stagingApp 'Microsoft.App/containerApps@2024-03-01' = {
  name: 'escrowrail-api-staging'
  location: location
  properties: {
    managedEnvironmentId: env.id
    configuration: {
      ingress: { external: true, targetPort: 8080 }
      secrets: secrets
      registries: registryConf
    }
    template: {
      containers: [{
        name: 'api'
        image: image
        resources: { cpu: '0.5', memory: '1Gi' }
        env: concat(sharedEnv, [
          { name: 'SPRING_PROFILES_ACTIVE', value: 'demo' }
          { name: 'ESCROWRAIL_CORS_ORIGINS', value: corsOrigins }
        ])
      }]
      scale: { minReplicas: 0, maxReplicas: 1 }
    }
  }
}

output prodFqdn string = prodApp.properties.configuration.ingress.fqdn
output stagingFqdn string = stagingApp.properties.configuration.ingress.fqdn
