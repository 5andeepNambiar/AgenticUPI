import os
import uuid
import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException, Depends
import httpx
from tenacity import retry, retry_if_exception_type, stop_after_attempt, wait_exponential

from .models import PaymentIntent
from .client import AgenticUpiClient

logger = logging.getLogger(__name__)

# Single httpx connection pool to avoid socket exhaustion (Interview Signal!)
http_client = httpx.AsyncClient(timeout=10.0)

@asynccontextmanager
async def lifespan(app: FastAPI):
    yield
    await http_client.aclose()

app = FastAPI(title="AI Agent - Pay Orchestrator", lifespan=lifespan)

# Injectable dependency
def get_upi_client() -> AgenticUpiClient:
    return AgenticUpiClient(
        base_url=os.getenv("AGENTIC_UPI_BASE_URL", "http://localhost:8080"),
        agent_id=os.getenv("AGENTIC_UPI_AGENT_ID", "agent_demo_1"),
        api_secret=os.getenv("AGENTIC_UPI_SECRET", "super_secret_kms_decrypted_value"),
        client=http_client
    )

# --- Idempotent Retry Policy ---
# Only retry on explicit Networking/Timeout errors.
# Do NOT retry on 409 Conflict, 429 Velocity, or 403 Policy failures.
@retry(
    retry=retry_if_exception_type(TimeoutError),
    stop=stop_after_attempt(3),
    wait=wait_exponential(multiplier=1, min=2, max=10),
    reraise=True
)
async def _execute_with_retry(client: AgenticUpiClient, intent: PaymentIntent, idemp_key: str):
    return await client.execute_payment(intent, idemp_key)

@app.post("/agent/execute-payment")
async def handle_agent_payment(intent: PaymentIntent, client: AgenticUpiClient = Depends(get_upi_client)):
    """
    Called by the LLM orchestrator when the AI decides a payment is needed.
    """

    # 1. State setup: Generate idempotency key ONCE before the retry block
    # By passing this explicitly, retries inside _execute_with_retry are guaranteed
    # to submit the exact same key to the gateway instead of triggering double charges.
    idempotency_key = f"idemp_{uuid.uuid4().hex}"

    logger.info(f"Initiating payment. Payer: {intent.payer_vpa}, IdempKey: {idempotency_key}")

    try:
        result = await _execute_with_retry(client, intent, idempotency_key)

        # Safe Audit logging (DO NOT log raw context, secrets, or sensitive payload here)
        logger.info(f"Payment success. Gateway TxnId: {result.get('txn_id')}")
        return {"status": "success", "gateway_response": result}

    except httpx.HTTPStatusError as e:
        status_code = e.response.status_code
        try:
            error_payload = e.response.json()
            error_code = error_payload.get('error_code', 'UNKNOWN')
        except Exception:
            error_code = "UNKNOWN_ERROR"

        logger.warning(f"Gateway rejected payment. HTTP Status: {status_code}, Payload: {error_code}")

        msg = "Payment Rejected"
        if status_code == 401:
            msg = "Agent Configuration Error"
        elif status_code == 403:
            msg = f"Policy/Mandate Verification Failed: {error_code}"
        elif status_code == 409:
            msg = "Idempotency or Request Conflict"
        elif status_code == 429:
            msg = "Velocity/Limit Exceeded"

        raise HTTPException(status_code=400, detail=msg)

    except TimeoutError:
        logger.error(f"Gateway timeout after retries. Manual recon required for idemp: {idempotency_key}")
        raise HTTPException(status_code=504, detail="Gateway timed out. Payment status unknown.")
    except Exception as e:
        logger.error(f"Unexpected internal error: {str(e)}")
        raise HTTPException(status_code=500, detail="Internal Agent Error")
