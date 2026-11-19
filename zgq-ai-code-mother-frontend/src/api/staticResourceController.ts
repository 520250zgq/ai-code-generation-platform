// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 此处后端没有提供注释 GET /static/${param0} */
export async function serveStaticResource(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.serveStaticResourceParams,
  options?: { [key: string]: any },
) {
  const { appId: param0, ...queryParams } = params
  return request<string>(`/static/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /static/${param0}/${&#42;resourcePath} */
export async function serveStaticResource1(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.serveStaticResource1Params,
  options?: { [key: string]: any },
) {
  const { appId: param0, resourcePath: param1, ...queryParams } = params
  return request<string>(
    `/static/${encodeURIComponent(param0)}/${param1.split('/').map(encodeURIComponent).join('/')}`,
    {
      method: 'GET',
      params: { ...queryParams },
      ...(options || {}),
    },
  )
}
