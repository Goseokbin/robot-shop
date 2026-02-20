import random
import threading

import instana
import os
import sys
import time
import logging
import uuid
import json
import requests
import traceback
import psutil
from flask import Flask
from flask import Response
from flask import request
from flask import jsonify
from rabbitmq import Publisher
# Prometheus
import prometheus_client
from prometheus_client import Counter, Histogram

app = Flask(__name__)
app.logger.setLevel(logging.INFO)

CART = os.getenv('CART_HOST', 'cart')
USER = os.getenv('USER_HOST', 'user')
PAYMENT_GATEWAY = os.getenv('PAYMENT_GATEWAY', 'https://paypal.com/')

# flagd Feature Flag — payment maintenance mode & memory leak scenario
FLAGD_OFREP_URL = os.getenv('FLAGD_OFREP_URL', '')
maintenance_mode = False

# Memory Leak scenario
_leaked_bytes = []
_memory_leak_triggered = False
MEMORY_LIMIT_MB = int(os.getenv('MEMORY_LIMIT_MB', '256'))

def _trigger_memory_leak(chunk_mb=25, count=4):
    """Allocate chunk_mb × count MB into _leaked_bytes (GC-proof)."""
    total = 0
    for _ in range(count):
        chunk = bytearray(chunk_mb * 1024 * 1024)
        _leaked_bytes.append(chunk)
        total += chunk_mb
    app.logger.warning('MEMORY LEAK: allocated {}MB ({} chunks x {}MB), total leaked chunks={}'.format(
        total, count, chunk_mb, len(_leaked_bytes)))

def _poll_flagd():
    global maintenance_mode, _memory_leak_triggered
    if not FLAGD_OFREP_URL:
        return
    maint_url = '{}/ofrep/v1/evaluate/flags/payment-maintenance'.format(FLAGD_OFREP_URL)
    memleak_url = '{}/ofrep/v1/evaluate/flags/scenario-memory-leak'.format(FLAGD_OFREP_URL)
    while True:
        # poll payment-maintenance flag
        try:
            resp = requests.post(maint_url, json={'context': {}}, timeout=3)
            if resp.status_code == 200:
                value = resp.json().get('value', False)
                if value != maintenance_mode:
                    app.logger.info('maintenance_mode changed: {} -> {}'.format(maintenance_mode, value))
                maintenance_mode = value
            else:
                app.logger.debug('flagd payment-maintenance returned {}'.format(resp.status_code))
        except Exception:
            pass
        # poll scenario-memory-leak flag
        try:
            resp = requests.post(memleak_url, json={'context': {}}, timeout=3)
            if resp.status_code == 200:
                value = resp.json().get('value', False)
                if value and not _memory_leak_triggered:
                    app.logger.info('scenario-memory-leak flag ON — triggering memory leak')
                    _memory_leak_triggered = True
                    _trigger_memory_leak()
                elif not value and _memory_leak_triggered:
                    app.logger.info('scenario-memory-leak flag OFF — resetting triggered state')
                    _memory_leak_triggered = False
            else:
                app.logger.debug('flagd scenario-memory-leak returned {}'.format(resp.status_code))
        except Exception:
            pass
        time.sleep(5)

if FLAGD_OFREP_URL:
    _flag_thread = threading.Thread(target=_poll_flagd, daemon=True)
    _flag_thread.start()
    app.logger.info('Started flagd polling thread: {}'.format(FLAGD_OFREP_URL))

# Prometheus
PromMetrics = {}
PromMetrics['SOLD_COUNTER'] = Counter('sold_count', 'Running count of items sold')
PromMetrics['AUS'] = Histogram('units_sold', 'Avergae Unit Sale', buckets=(1, 2, 5, 10, 100))
PromMetrics['AVS'] = Histogram('cart_value', 'Avergae Value Sale', buckets=(100, 200, 500, 1000, 2000, 5000, 10000))


@app.errorhandler(Exception)
def exception_handler(err):
    app.logger.error(str(err))
    return str(err), 500

@app.route('/health', methods=['GET'])
def health():
    return 'OK'

# Prometheus
@app.route('/metrics', methods=['GET'])
def metrics():
    res = []
    for m in PromMetrics.values():
        res.append(prometheus_client.generate_latest(m))

    return Response(res, mimetype='text/plain')


@app.route('/scenario/memory-leak', methods=['GET'])
def memory_leak():
    chunk_mb = int(request.args.get('chunkMB', 25))
    count = int(request.args.get('count', 4))
    _trigger_memory_leak(chunk_mb, count)
    rss_mb = psutil.Process().memory_info().rss / (1024 * 1024)
    return jsonify({
        'allocated': '{}MB ({} x {}MB)'.format(chunk_mb * count, count, chunk_mb),
        'totalLeakedChunks': len(_leaked_bytes),
        'rssMB': round(rss_mb, 1),
        'limitMB': MEMORY_LIMIT_MB,
    })

@app.route('/scenario/memory-check', methods=['GET'])
def memory_check():
    rss_mb = psutil.Process().memory_info().rss / (1024 * 1024)
    usage_pct = (rss_mb / MEMORY_LIMIT_MB) * 100
    result = {
        'rssMB': round(rss_mb, 1),
        'limitMB': MEMORY_LIMIT_MB,
        'usagePercent': round(usage_pct, 1),
        'leakedChunks': len(_leaked_bytes),
    }
    if usage_pct > 80:
        raise Exception('MEMORY CRITICAL: RSS={:.0f}MB, limit={}MB, usage={:.1f}%'.format(
            rss_mb, MEMORY_LIMIT_MB, usage_pct))
    result['status'] = 'HEALTHY'
    return jsonify(result)

@app.route('/maintenance-status', methods=['GET'])
def maintenance_status():
    return jsonify({'maintenance': maintenance_mode})

@app.route('/pay/<id>', methods=['POST'])
def pay(id):
    if maintenance_mode:
        return jsonify({'error': 'Payment system is under maintenance'}), 503
    app.logger.info('payment for {}'.format(id))
    cart = request.get_json()
    app.logger.info(cart)

    anonymous_user = True

    # check user exists
    try:
        req = requests.get('http://{user}:8080/check/{id}'.format(user=USER, id=id))
    except requests.exceptions.RequestException as err:
        app.logger.error(err)
        return str(err), 500
    if req.status_code == 200:
        anonymous_user = False

    # check that the cart is valid
    # this will blow up if the cart is not valid
    has_shipping = False
    for item in cart.get('items'):
        if item.get('sku') == 'SHIP':
            has_shipping = True

    if cart.get('total', 0) == 0 or has_shipping == False:
        app.logger.warn('cart not valid')
        return 'cart not valid', 400

    # dummy call to payment gateway, hope they dont object
    try:
        req = requests.get(PAYMENT_GATEWAY)
        app.logger.info('{} returned {}'.format(PAYMENT_GATEWAY, req.status_code))
    except requests.exceptions.RequestException as err:
        app.logger.error(err)
        return str(err), 500
    if req.status_code != 200:
        return 'payment error', req.status_code

    # Prometheus
    # items purchased
    item_count = countItems(cart.get('items', []))
    PromMetrics['SOLD_COUNTER'].inc(item_count)
    PromMetrics['AUS'].observe(item_count)
    PromMetrics['AVS'].observe(cart.get('total', 0))

    # Generate order id
    orderid = str(uuid.uuid4())
    queueOrder({ 'orderid': orderid, 'user': id, 'cart': cart })

    # add to order history
    if not anonymous_user:
        try:
            req = requests.post('http://{user}:8080/order/{id}'.format(user=USER, id=id),
                    data=json.dumps({'orderid': orderid, 'cart': cart}),
                    headers={'Content-Type': 'application/json'})
            app.logger.info('order history returned {}'.format(req.status_code))
        except requests.exceptions.RequestException as err:
            app.logger.error(err)
            return str(err), 500

    # delete cart
    try:
        req = requests.delete('http://{cart}:8080/cart/{id}'.format(cart=CART, id=id));
        app.logger.info('cart delete returned {}'.format(req.status_code))
    except requests.exceptions.RequestException as err:
        app.logger.error(err)
        return str(err), 500
    if req.status_code != 200:
        return 'order history update error', req.status_code

    return jsonify({ 'orderid': orderid })


def queueOrder(order):
    app.logger.info('queue order')

    # For screenshot demo requirements optionally add in a bit of delay
    delay = int(os.getenv('PAYMENT_DELAY_MS', 0))
    time.sleep(delay / 1000)

    headers = {}
    publisher.publish(order, headers)


def countItems(items):
    count = 0
    for item in items:
        if item.get('sku') != 'SHIP':
            count += item.get('qty')

    return count


# RabbitMQ
publisher = Publisher(app.logger)

if __name__ == "__main__":
    sh = logging.StreamHandler(sys.stdout)
    sh.setLevel(logging.INFO)
    fmt = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    app.logger.info('Payment gateway {}'.format(PAYMENT_GATEWAY))
    port = int(os.getenv("SHOP_PAYMENT_PORT", "8080"))
    app.logger.info('Starting on port {}'.format(port))
    app.run(host='0.0.0.0', port=port)
