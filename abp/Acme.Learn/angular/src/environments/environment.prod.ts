import { Environment } from '@abp/ng.core';

const baseUrl = 'http://localhost:4200';

const oAuthConfig = {
  issuer: 'https://localhost:44362/',
  redirectUri: baseUrl,
  clientId: 'Learn_App',
  responseType: 'code',
  scope: 'offline_access Learn',
  requireHttps: true,
};

export const environment = {
  production: true,
  application: {
    baseUrl,
    name: 'Learn',
  },
  oAuthConfig,
  apis: {
    default: {
      url: 'https://localhost:44362',
      rootNamespace: 'Acme.Learn',
    },
    AbpAccountPublic: {
      url: oAuthConfig.issuer,
      rootNamespace: 'AbpAccountPublic',
    },
  },
  remoteEnv: {
    url: '/getEnvConfig',
    mergeStrategy: 'deepmerge'
  }
} as Environment;
