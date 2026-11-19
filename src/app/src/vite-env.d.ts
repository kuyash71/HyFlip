/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SERVER_URL?: string;
  readonly VITE_SERVER_TOKEN?: string;
  /** Source repository, for the Credits links; set by the release build. */
  readonly VITE_REPO_URL?: string;
  readonly VITE_APP_VERSION?: string;
}
