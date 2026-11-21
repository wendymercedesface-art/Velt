"""Run against a dedicated Android test device with the app and instrumentation APK installed."""
import argparse, hashlib, io, os, struct, subprocess, threading, time, uuid, wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def run():
    p=argparse.ArgumentParser()
    p.add_argument("--serial",required=True)
    p.add_argument("--reboot",action="store_true",help="Also reboot this test device and verify recovery")
    args=p.parse_args()
    binary=os.environ.get("ADB","adb")
    def adb(*s,timeout=200):
        return subprocess.check_output([binary,"-s",args.serial,*s],timeout=timeout,text=True).strip()
    f=io.BytesIO()
    with wave.open(f,"wb") as w:
        w.setparams((1,2,44100,0,"NONE","")); w.writeframes(struct.pack("<h",1000)*44100*14)
    data=f.getvalue()
    counts=set()
    restarted=threading.Event()
    restart_served=threading.Event()
    class Origin(BaseHTTPRequestHandler):
        def do_HEAD(self): self.send(False)
        def do_GET(self): self.send(True)
        def send(self,body):
            if "/restart" in self.path and not restarted.wait(90):self.send_error(503);return
            if "/missing" in self.path:self.send_error(404);return
            if "/server" in self.path:self.send_error(503);return
            r=self.headers.get("Range",""); start=0;end=len(data)-1
            if r:
                a,b=r.replace("bytes=","").split("-");start=int(a);end=int(b or end)
            self.send_response(206 if r else 200)
            self.send_header("Content-Type","audio/wav");self.send_header("ETag",'"test-wav"');self.send_header("Content-Length",str(end-start+1))
            if r:self.send_header("Content-Range",f"bytes {start}-{end}/{len(data)}")
            self.end_headers()
            if body:
                if "/interrupted" in self.path and not r and self.path not in counts:
                    counts.add(self.path);self.wfile.write(data[:64000]);return
                try:
                    for i in range(start,end+1,16384):
                        self.wfile.write(data[i:min(i+16384,end+1)])
                        if "slow" in self.path:time.sleep(.085)
                    if "/restart" in self.path and end-start+1==len(data):restart_served.set()
                except (BrokenPipeError,ConnectionResetError):pass
        def log_message(self,*args):pass
    with ThreadingHTTPServer(("127.0.0.1",0),Origin) as http:
        threading.Thread(target=http.serve_forever,daemon=True).start();port=http.server_port
        def reverse():adb("reverse",f"tcp:{port}",f"tcp:{port}")
        def phase(name):
            result=adb("shell","am","instrument","-w","-e","phase",name,"-e","url",f"http://127.0.0.1:{port}","-e","hash",hashlib.sha256(data).hexdigest(),"com.veltrax.music.test/com.veltrax.music.TransferChecks")
            print(result,flush=True)
            if "OK" not in result.splitlines():raise RuntimeError("Falló la prueba "+name)
        reverse();phase("suite")
        if args.reboot:
            phase("prepare");boot=adb("shell","cat","/proc/sys/kernel/random/boot_id");adb("reboot")
            limit=time.monotonic()+360
            while time.monotonic()<limit:
                try:
                    now=adb("shell","cat","/proc/sys/kernel/random/boot_id",timeout=5)
                    if now!=boot and len(now)==36:
                        restarted.set();reverse()
                        if adb("shell","getprop","sys.boot_completed")=="1":break
                except (subprocess.SubprocessError,OSError):pass
                time.sleep(2)
            else:raise RuntimeError("Android no arrancó")
            adb("shell","input","keyevent","82")
            if not restart_served.wait(140):raise RuntimeError("La descarga no volvió a la fuente tras el reinicio")
            print("La fuente entregó los bytes después de reiniciar Android.",flush=True)
            phase("verify")
if __name__=="__main__":run()
