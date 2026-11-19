#!/usr/bin/env python3
"""Fetch official resources, calculate integrity, verify mirrors; never invent mirror addresses.
Usage: python scripts/make_manifest.py [--model tr] [--manifest PATH]
Downloads can total several GB; use --model for a focused verification.
"""
import argparse, hashlib, json, urllib.request
from pathlib import Path
p=argparse.ArgumentParser(); p.add_argument('--model'); p.add_argument('--manifest',type=Path,default=Path(__file__).resolve().parents[1]/'app/src/main/assets/models_manifest.json'); args=p.parse_args()
data=json.loads(args.manifest.read_text())
for name,resources in data['models'].items():
    if args.model and name!=args.model: continue
    for resource in resources:
        baseline=None
        for mirror in sorted(resource['mirrors'],key=lambda x:x['priority']):
            sha=hashlib.sha256(); size=0
            req=urllib.request.Request(mirror['url'],headers={'User-Agent':'AuraOcrLab/0.3.3','Accept-Encoding':'identity'})
            with urllib.request.urlopen(req,timeout=30) as response:
                while block:=response.read(1024*1024):
                    size+=len(block); sha.update(block)
            found=(size,sha.hexdigest())
            if baseline is None: baseline=found
            elif found!=baseline: raise ValueError(f"Mirror differs: {name}/{mirror['id']}")
            print(name,mirror['id'],size,sha.hexdigest())
        resource['sizeBytes'],resource['sha256']=baseline
args.manifest.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
