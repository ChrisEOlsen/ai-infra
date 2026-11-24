import unittest, subprocess, tempfile, pathlib, json, os

ROOT = pathlib.Path(__file__).resolve().parents[1]


class ShutdownSafeguards(unittest.TestCase):
    def call(self, *args):
        with tempfile.TemporaryDirectory() as directory:
            config = pathlib.Path(directory) / "customer.json"
            config.write_text(
                json.dumps(
                    {
                        "customer": "test-customer",
                        "domain": "assistant.example.com",
                        "region": "us-east-1",
                    }
                )
            )
            return subprocess.run(
                [str(ROOT / "ops/rag"), "decommission", str(config), *args],
                capture_output=True,
                text=True,
            )

    def test_decommission_requires_explicit_retention_and_customer_confirmation(self):
        for args in [
            (),
            ("--retain-data",),
            ("--delete-data", "--confirm", "wrong-customer"),
        ]:
            result = self.call(*args)
            self.assertEqual(result.returncode, 2)
            self.assertIn("Choose --retain-data", result.stderr)

    def test_external_credentials_must_be_reviewed(self):
        result = self.call("--retain-data", "--confirm", "test-customer")
        self.assertEqual(result.returncode, 2)
        self.assertIn("credentials", result.stderr)


class CustomerStateIsolation(unittest.TestCase):
    def test_each_customer_gets_its_own_backend_and_secret_target(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = pathlib.Path(directory).resolve()
            log = temp / "calls.jsonl"
            terraform = temp / "terraform"
            terraform.write_text(
                """#!/usr/bin/env python3
import os,sys,json,pathlib
state=os.environ['TF_DATA_DIR'];customer=pathlib.Path(state).parts[-3]
with open(os.environ['CALL_LOG'],'a')as log:log.write(json.dumps({'tool':'terraform','state':state})+'\\n')
if 'output' in sys.argv:print(json.dumps({k:{'value':v}for k,v in {'customer':customer,'secret_arn':'secret-'+customer}.items()}))
"""
            )
            aws = temp / "aws"
            aws.write_text(
                """#!/usr/bin/env python3
import os,sys,json
with open(os.environ['CALL_LOG'],'a')as log:log.write(json.dumps({'tool':'aws','args':sys.argv[1:]})+'\\n')
print('{}')
"""
            )
            for tool in [terraform, aws]:
                tool.chmod(0o755)
            secret = temp / "credentials.json"
            secret.write_text(
                json.dumps(
                    {
                        key: "test-value"
                        for key in [
                            "POSTGRES_PASSWORD",
                            "RAG_DB_PASSWORD",
                            "KEYCLOAK_DB_PASSWORD",
                            "OIDC_CLIENT_SECRET",
                            "KEYCLOAK_ADMIN_PASSWORD",
                            "COMPANY_ADMIN_PASSWORD",
                            "PROVISIONER_SECRET",
                            "MODEL_API_KEY",
                        ]
                    }
                )
            )
            environment = {
                **os.environ,
                "PATH": str(temp) + os.pathsep + os.environ["PATH"],
                "TERRAFORM": str(terraform),
                "COMPANY_RAG_STATE_DIR": str(temp / "state"),
                "CALL_LOG": str(log),
            }
            for customer in ["customer-one", "customer-two"]:
                config = temp / (customer + ".json")
                config.write_text(
                    json.dumps(
                        {
                            "customer": customer,
                            "domain": "assistant.example.com",
                            "region": "us-east-1",
                        }
                    )
                )
                result = subprocess.run(
                    [
                        str(ROOT / "ops/rag"),
                        "set-secret",
                        str(config),
                        "--secret-file",
                        str(secret),
                    ],
                    env=environment,
                    text=True,
                    capture_output=True,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertNotIn("test-value", result.stdout)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            states = {call["state"] for call in calls if call["tool"] == "terraform"}
            self.assertEqual(
                states,
                {
                    str(temp / "state" / name / "storage" / ".terraform")
                    for name in ["customer-one", "customer-two"]
                },
            )
            secret_targets = [
                call["args"][call["args"].index("--secret-id") + 1]
                for call in calls
                if call["tool"] == "aws"
            ]
            self.assertEqual(
                secret_targets, ["secret-customer-one", "secret-customer-two"]
            )


if __name__ == "__main__":
    unittest.main()
