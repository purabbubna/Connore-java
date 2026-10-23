const Api={
  async call(method,path,body){
    const options={method,credentials:'same-origin',headers:{'X-Connore-Request':'1'}};
    if(body!==undefined){options.headers['Content-Type']='application/json';options.body=JSON.stringify(body);}
    const response=await fetch('/api'+path,options);const data=response.status===204?null:await response.json();
    if(!response.ok){const error=new Error(data?.error||'REQUEST_FAILED');error.code=data?.error;error.status=response.status;throw error;}
    return data;
  }
};
