"""RocketMQ群晖开发配置契约；不代替注册与消息发收验收。"""
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2] / 'deploy/synology-rocketmq'


class SynologyRocketMQTests(unittest.TestCase):
    def test_remoting_network_and_persistent_volumes(self):
        config = yaml.safe_load((ROOT / 'docker-compose.yml').read_text())
        services = config['services']
        self.assertEqual(set(services), {'init-permissions', 'namesrv', 'broker'})
        initializer = services['init-permissions']
        self.assertEqual(initializer['profiles'], ['setup'])
        self.assertEqual(initializer['user'], '0:0')
        self.assertEqual(initializer['restart'], 'no')
        self.assertIn('chown 3000:3000', initializer['command'][-1])
        self.assertNotIn('chmod', initializer['command'][-1])
        for name in ('namesrv', 'broker'):
            self.assertNotIn('user', services[name])
        for service in services.values():
            self.assertEqual(service['image'], 'apache/rocketmq:5.3.1')
            self.assertEqual(service['platform'], 'linux/amd64')
            self.assertNotIn('privileged', service)
        self.assertIn('broker-store:/home/rocketmq/store', services['broker']['volumes'])
        self.assertIn('${RMQ_BIND_IP:?填写群晖内网 IP}:10911:10911', services['broker']['ports'])
        self.assertIn('${RMQ_BIND_IP:?填写群晖内网 IP}:9876:9876', services['namesrv']['ports'])
        broker = (ROOT / 'broker.conf').read_text()
        self.assertIn('brokerIP1=192.168.3.40', broker)
        self.assertIn('listenPort=10911', broker)
        self.assertIn('autoCreateTopicEnable=false', broker)
        self.assertIn('aclEnable=false', broker)
        self.assertIn('不启用ACL', (ROOT / 'README.md').read_text())


if __name__ == '__main__':
    unittest.main()
