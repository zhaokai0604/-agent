#!/usr/bin/env python3
"""Root Helper 客户端：向 Unix Socket 发送单条命令。"""
import socket
import sys

socket_path = sys.argv[1]
command = sys.argv[2]
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect(socket_path)
s.sendall(command.encode("utf-8"))
data = s.recv(65536).decode("utf-8", errors="replace")
sys.stdout.write(data)
s.close()
