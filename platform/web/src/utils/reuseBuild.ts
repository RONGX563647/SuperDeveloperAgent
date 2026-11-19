/**
 * 「复用产物部署」入口的可见性与取产物规则。
 *
 * <p>背景（BUG-12）：同样的代码要发到另一个环境、或只是想重发一次，
 * 重新构建要几分钟且会再产一次镜像；而这次运行其实已经成功、产物就在本机。
 * 但入口不能无条件出现——未成功的运行没有可复用产物，给它按钮只会点出一个错误。
 * 抽成纯函数便于回归：视图与脚本共用同一份判断。
 */

/** 只有成功的运行才有可复用产物。 */
export function canReuseBuild(status: string | undefined): boolean {
  return status === 'SUCCEEDED'
}

export interface ArtifactLike {
  kind: string
  ref: string
}

/**
 * 从产物列表里取要复用的镜像 tag。
 *
 * <p>取最后一个 image（与后端「以最后产出的镜像为准」的口径一致）；
 * 没有镜像产物时返回空串——调用方据此提示「这次只跑了检查构建」，
 * 而不是让用户点下去才看到一个后端错误。
 */
export function pickReusableImage(artifacts: ArtifactLike[] | undefined): string {
  if (!artifacts?.length) return ''
  const images = artifacts.filter((a) => a.kind === 'image')
    .map((a) => (a.ref || '').trim())
    .filter(Boolean)
  return images.at(-1) ?? ''
}
