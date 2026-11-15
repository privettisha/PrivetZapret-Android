#!/usr/bin/env python3
import socket

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 39876))
while True:
    data, peer = sock.recvfrom(65535)
    sock.sendto(data, peer)
