import { useEffect } from "react";
import { useNavigate, useParams } from "react-router-dom";

/**
 * Deep link: /contracts/:id redirects to the dashboard with the execution
 * drawer open on the same contract. The drawer is the single detail
 * surface — this route only exists so old links keep working.
 */
export default function ContractDetail() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  useEffect(() => {
    navigate(`/dashboard?trade=${id}`, { replace: true });
  }, [id, navigate]);
  return null;
}
