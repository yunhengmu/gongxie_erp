from fastapi import APIRouter, Depends, UploadFile, File
from app.schemas.common import ApiResponse
from app.dependencies import get_rag_service
from app.services.rag_service import RAGService
from app.services.upload_file_service import UploadFileService


router = APIRouter(prefix="/v1/rag/documents" , tags=["文件上传"])
@router.post("/upload")
async def upload_documents(
    files: list[UploadFile] = File(...),
    rag_service: RAGService = Depends(get_rag_service),
):
    upload_service = UploadFileService(rag_service)
    trunkNum = await upload_service.load_document(files)
    return ApiResponse(message="上传成功,片数:{}".format(trunkNum), data=trunkNum)

@router.get("/upload_ask")
async def upload_document_ask(
    file: list[UploadFile] = File(...),
    question: str = "",
    rag_service: RAGService = Depends(get_rag_service),
):
   upload_service = UploadFileService(rag_service)
   return ApiResponse(message="上传成功", data= upload_service.load_document_ask(file, question))