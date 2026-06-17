import subprocess

try:
    output = subprocess.check_output(['docker', 'compose', 'logs', '--since', '10m', 'backend'], stderr=subprocess.STDOUT)
    lines = output.decode('utf-8').splitlines()
    for line in lines:
        if 'f5759200' in line or 'unexpected message' in line or 'MatrixRouteService' in line or 'MatrixQueueService' in line or 'RcsExpirationService' in line:
            print(line)
except subprocess.CalledProcessError as e:
    print("Error:", e.output.decode('utf-8'))
except Exception as e:
    print("Exception:", e)
