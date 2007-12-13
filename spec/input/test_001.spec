// รหัสอักษร UTF-8

นำเข้า Hangman

อธิบาย คนแขวนคอ ดังนี้
  ก่อน(:ทุกอย่าง) ให้ทำ
    คนแขวนคอ = สร้าง คนแขวนคอ()
  จบ

  หลัง(:ทุกอย่าง) ให้ทำ
    คนแขวนคอ = ว่าง
  จบ

  กำหนดให้ ควรมีการตั้งค่าให้เรียบร้อย โดย
    คนแขวนคอ.ตั้งค่าคำ "hello"
    คนแขวนคอ.คำ ควร == "hello"
    คนแขวนคอ.ผิด ควร == 0.ครั้ง
    คนแขวนคอ.เดามากสุด ควร == 12.ครั้ง
    คนแขวนคอ.คำซ่อน ควร == ['_', '_', '_', '_', '_']
    คนแขวนคอ.คำืทั้งหมด ควร == [ก:"hello"]    
    คนแขวนคอ.คำืทั้งหมด ควร != [:]    
    คนแขวนคอ.จบแล้ว ควร == เท็จ
  จบ

  กำหนดให้ ควรตั้งค่าตัวตัวนับผิดเสียใหม่ ถ้าผู้ใช้อยากเริ่มเกมอีกครั้ง โดย
    คนแขวนคอ.ตั้งค่าคำ "bonjour"
    คนแขวนคอ.ลอง("a")
    คนแขวนคอ.ผิด ควร == 1
    คนแขวนคอ.เริ่มเกมใหม่()
    คนแขวนคอ.ผิด ควร == 0
  จบ
จบ