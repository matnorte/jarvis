/** The portable launcher's extracted executable disappears after Quit. */
export function startupOptions({packaged,smoke,enabled,executable,portable}) {
  if(!packaged||smoke)return null;
  return {openAtLogin:enabled,path:portable||executable,args:['--startup']};
}
